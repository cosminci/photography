package photography

import cats.syntax.all.*
import com.tngtech.archunit.core.importer.{ClassFileImporter, ImportOption}
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import munit.FunSuite
import photography.domain.album.Album
import scala.jdk.CollectionConverters.*

class ArchitectureTest extends FunSuite:

  private val testCodeLocation                 = classOf[ArchitectureTest].getProtectionDomain.getCodeSource.getLocation.toURI.toString
  private val excludeTestClasses: ImportOption = location => !location.contains(testCodeLocation)

  private val classes = ClassFileImporter().withImportOption(excludeTestClasses).importPackages("photography")

  test("should keep layers pointing inward and adapters isolated from each other"):
    assertNotEquals(testCodeLocation, classOf[Album].getProtectionDomain.getCodeSource.getLocation.toURI.toString)
    val doobieTargets = classes.iterator.asScala
      .filter(_.getPackageName.startsWith("photography.adapters.sqlite"))
      .flatMap(_.getDirectDependenciesFromSelf.asScala.map(_.getTargetClass))
      .filter(_.getName.startsWith("org.typelevel.doobie.")).toVector

    assert(doobieTargets.nonEmpty)
    assert(doobieTargets.forall(resideInAnyPackage("org.typelevel.doobie..").test))
    noClasses()
      .that()
      .resideInAPackage("photography.domain..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage(
        "photography.capabilities..",
        "photography.ports..",
        "photography.usecases..",
        "photography.adapters..",
        "photography.app..",
        "org.typelevel.doobie.."
      )
      .check(classes)
    noClasses()
      .that()
      .resideInAPackage("photography.capabilities..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage(
        "photography.domain..",
        "photography.ports..",
        "photography.usecases..",
        "photography.adapters..",
        "photography.app..",
        "org.typelevel.doobie.."
      )
      .check(classes)
    noClasses()
      .that()
      .resideInAPackage("photography.ports..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage(
        "photography.capabilities..",
        "photography.usecases..",
        "photography.adapters..",
        "photography.app..",
        "org.typelevel.doobie.."
      )
      .check(classes)
    noClasses()
      .that()
      .resideInAPackage("photography.usecases..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage(
        "photography.adapters..",
        "photography.app..",
        "org.typelevel.doobie..",
        "java.awt..",
        "javax.imageio..",
        "com.drew..",
        "com.twelvemonkeys.."
      )
      .check(classes)
    noClasses()
      .that()
      .resideInAPackage("photography.adapters.sqlite..")
      .should()
      .dependOnClassesThat()
      .resideInAPackage("photography.usecases..")
      .check(classes)
    slices().matching("photography.adapters.(*)..").should().notDependOnEachOther().check(classes)
    noClasses().that().resideInAPackage("photography.domain..").should().dependOnClassesThat().haveSimpleName("Ordering").check(classes)
    val capabilities = Set(
      "cats.Monad",
      "cats.MonadError",
      "cats.Functor",
      "cats.effect.kernel.Async",
      "cats.effect.kernel.MonadCancel",
      "cats.effect.kernel.Sync",
      "cats.effect.kernel.Clock",
      "cats.effect.std.Mutex",
      "org.typelevel.log4cats.Logger",
      "photography.capabilities.IdGenerator",
      "photography.capabilities.Clock",
      "photography.capabilities.Transaction"
    )
    val implementations = classes.iterator.asScala.filter(_.getSimpleName.startsWith("Live")).toVector
    assert(implementations.nonEmpty)
    implementations.foreach { implementation =>
      implementation.getConstructors.iterator.asScala.foreach { constructor =>
        val dependencies = constructor.getRawParameterTypes.iterator.asScala.filterNot(value => capabilities.contains(value.getName)).toVector
        val maximum      = if implementation.getName === "photography.usecases.spread.SpreadManager$LiveSpreadManager" then 6 else 5
        assert(dependencies.sizeIs <= maximum, s"${implementation.getName} injects ${dependencies.size} collaborators: $dependencies")
      }
    }
