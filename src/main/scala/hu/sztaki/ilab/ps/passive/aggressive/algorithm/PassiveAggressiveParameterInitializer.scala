package hu.sztaki.ilab.ps.passive.aggressive.algorithm

import breeze.linalg.{DenseVector, Vector}

object PassiveAggressiveParameterInitializer {

  def initBinary: Int => Double =
    _ => 0

  def initMulti(labelCount: Int): Int => Vector[Double] =
    _ => DenseVector.zeros[Double](labelCount)

}
