package com.mio.irondome.impacto

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.util.concurrent.TimeUnit

class DependencyAnalyzerTest : LightJavaCodeInsightFixtureTestCase() {

    private lateinit var testRoot: VirtualFile

    override fun setUp() {
        super.setUp()
        myFixture.addClass("package app.data; public interface Repository { }")
        myFixture.addClass("package app.data; public class SqlRepository implements Repository { }")
        myFixture.addClass(
            """
            package app.core;
            import app.data.Repository;
            public class Service {
                private final Repository repository;
                private final Helper helper = new Helper();
                public Service(Repository repository) { this.repository = repository; }
                public static Service create() { return null; }
                static class Nested { Formatter formatter; }
            }
            """.trimIndent(),
        )
        myFixture.addClass("package app.core; public class Helper { Service service; }")
        myFixture.addClass("package app.core; public abstract class Formatter { }")
        myFixture.addClass("package app.web; public class Controller { app.core.Service service = app.core.Service.create(); }")
        myFixture.addClass("package app.web; public class Unrelated { String text; }")

        testRoot = myFixture.tempDirFixture.findOrCreateDir("tests")
        PsiTestUtil.addSourceRoot(module, testRoot, true)
        myFixture.addFileToProject("tests/app/core/ServiceTest.java", "package app.core; public class ServiceTest { Service service; }")
        myFixture.addFileToProject("tests/app/web/FlowTest.java", "package app.web; public class FlowTest { app.core.Helper helper; }")
    }

    override fun tearDown() {
        try {
            PsiTestUtil.removeSourceRoot(module, testRoot)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testEfferentAndAfferentAroundFocus() {
        val graph = analyze("app.core.Service", depth = 1)
        val names = graph.nodes.map { it.qualifiedName }.toSet()

        assertEquals(
            setOf("app.core.Service", "app.data.Repository", "app.core.Helper", "app.core.Formatter", "app.web.Controller"),
            names,
        )
        val service = graph.nodes.single { it.qualifiedName == "app.core.Service" }
        assertEquals(0, service.level)
        assertEquals("Eferentes: Repository, Helper e Formatter (via classe aninhada)", 3, service.efferent)
        assertEquals("Aferentes: Helper e Controller, sem contar ServiceTest", 2, service.afferent)
    }

    fun testImplementsEdgeAndKinds() {
        val graph = analyze("app.data.SqlRepository", depth = 1)

        val edge = graph.edges.single { it.from == "app.data.SqlRepository" && it.to == "app.data.Repository" }
        assertEquals(EdgeKind.IMPLEMENTS, edge.kind)
        assertEquals(ClassKind.INTERFACE, graph.nodes.single { it.name == "Repository" }.kind)
    }

    fun testAttributeBecomesAssociationAndOtherUseStaysUsage() {
        val graph = analyze("app.core.Service", depth = 1)

        assertEquals(EdgeKind.ASSOCIATION, graph.edges.single { it.from == "app.core.Service" && it.to == "app.data.Repository" }.kind)
        assertEquals(EdgeKind.USAGE, graph.edges.single { it.from == "app.core.Service" && it.to == "app.core.Formatter" }.kind)
    }

    fun testMembersAreListedLikeAClassDiagram() {
        val service = analyze("app.core.Service", depth = 1).nodes.single { it.name == "Service" }

        assertTrue(service.attributes.contains("- repository: Repository"))
        assertTrue(service.operations.contains("+ create(): Service"))
        assertTrue(service.operations.contains("+ Service(Repository)"))
    }

    private fun analyze(focus: String, depth: Int): DependencyGraph =
        inBackground { DependencyAnalyzer(project, EmptyProgressIndicator()).analyze(focus, depth) }

    /** A análise usa read actions não bloqueantes, que não podem rodar na EDT do teste. */
    private fun <T> inBackground(action: () -> T): T {
        val future = ApplicationManager.getApplication().executeOnPooledThread<T> { action() }
        return PlatformTestUtil.waitForFuture(future, TimeUnit.SECONDS.toMillis(60))
    }
}
