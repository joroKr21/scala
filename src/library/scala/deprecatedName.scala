/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc.
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala

import scala.annotation.meta._


 /** An annotation that designates that the name of a parameter is deprecated.
  *
  *  Using this name in a named argument generates a deprecation warning.
  *
  *  Library authors should state the library's deprecation policy in their documentation to give
  *  developers guidance on how long a deprecated name will be preserved.
  *
  *  Library authors should prepend the name of their library to the version number to help
  *  developers distinguish deprecations coming from different libraries:
  *
  *  {{{
  *  def inc(x: Int, @deprecatedName('y, "FooLib 12.0") n: Int): Int = x + n
  *  inc(1, y = 2)
  *  }}}
  *  will produce the following warning:
  *  {{{
  *  warning: the parameter name y is deprecated (since FooLib 12.0): use n instead
  *  inc(1, y = 2)
  *           ^
  *  }}}
  *
  *  @since  2.8.1
  *  @see    [[scala.deprecated]]
  *  @see    [[scala.deprecatedInheritance]]
  *  @see    [[scala.deprecatedOverriding]]
  */
@param
class deprecatedName(name: Symbol = Symbol("<none>"), since: String = "") extends scala.annotation.StaticAnnotation
