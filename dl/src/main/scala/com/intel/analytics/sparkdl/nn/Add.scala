/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intel.analytics.sparkdl.nn

import com.intel.analytics.sparkdl.tensor.Tensor
import com.intel.analytics.sparkdl.tensor.TensorNumericMath.TensorNumeric
import com.intel.analytics.sparkdl.utils.RandomGenerator._

import scala.reflect.ClassTag

class Add[@specialized(Float, Double) T: ClassTag](inputSize: Int,
  private var initMethod: InitializationMethod = Default)(
  implicit ev: TensorNumeric[T]) extends Module[T] {

  val bias = Tensor[T](inputSize)
  val ones = Tensor[T](1).fill(ev.fromType[Int](1))
  this.gradBias = Tensor[T](inputSize)

  reset()

  def setInitMethod(initMethod: InitializationMethod): this.type = {
    this.initMethod = initMethod
    this
  }

  override def reset(): Unit = {
    initMethod match {
      case Default =>
        val stdv = 1 / math.sqrt(bias.size(1))
        bias.apply1(_ => ev.fromType[Double](RNG.uniform(-stdv, stdv)))
      case Xavier =>
        val fanIn = bias.size(2)
        val fanOut = bias.size(1)
        val stdv = math.sqrt(6.0 / (fanIn + fanOut))
        bias.apply1(_ => ev.fromType[Double](RNG.uniform(-stdv, stdv)))
    }
  }

  override def updateOutput(input: Tensor[T]): Tensor[T] = {
    output.resizeAs(input).copy(input)
    if (input.isSameSizeAs(bias)) {
      output.add(bias)
    } else {
      val batchSize = input.size(1)
      if (ones.size(1) != batchSize) ones.resize(batchSize).fill(ev.fromType[Int](1))
      bias.view(bias.size.product)
      output.view(batchSize, output.size.product)
      output.addr(ev.fromType[Int](1), ones, bias)
    }
    output
  }

  override def updateGradInput(input: Tensor[T], gradOutput: Tensor[T]): Tensor[T] = {
    gradInput.resizeAs(gradOutput)
    gradInput.copy(gradOutput)
    gradInput
  }

  override def accGradParameters(input: Tensor[T], gradOutput: Tensor[T],
                                 scale: Double = 1.0): Unit = {

    if (gradBias.size(1) == 1) {
      gradBias(1) = gradBias(1).add(ev.times(ev.fromType[Double](scale), gradOutput.sum()))
    } else {
      if (input.isSameSizeAs(bias)) {
        gradBias.add(ev.fromType[Double](scale), gradOutput)
      } else {
         gradOutput.view(input.size(1), gradOutput.size.product)
         gradBias.view(gradBias.size().product).addmv(ev.fromType(scale), gradOutput.t(), ones)
      }
    }
  }

  override def toString(): String = {
    s"nn.Add"
  }
}
