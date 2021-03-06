// scalac: -language:experimental.macros
import scala.reflect.runtime.universe._
import scala.reflect.macros.blackbox.Context

object Impls {
  def foo[U: c.WeakTypeTag](c: Context)(x: c.Expr[U]) = {
    import c.universe._
    val U = implicitly[c.WeakTypeTag[U]]
    val body = Apply(Select(Ident(definitions.PredefModule), TermName("println")), List(Literal(Constant(U.toString))))
    c.Expr[Unit](body)
  }
}
