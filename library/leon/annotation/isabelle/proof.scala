/* Copyright 2009-2016 EPFL, Lausanne */
package leon.annotation.isabelle

import leon.annotation._

import scala.annotation.StaticAnnotation
import scala.Predef.String

@ignore
class proof(method: String, kind: String = "") extends StaticAnnotation
