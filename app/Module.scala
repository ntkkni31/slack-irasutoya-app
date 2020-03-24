import com.google.inject.AbstractModule
import controllers.tamagoya.TamagoyaApp

class Module extends AbstractModule {

  override def configure(): Unit = {
    bind(classOf[TamagoyaApp]).asEagerSingleton()
  }
}
