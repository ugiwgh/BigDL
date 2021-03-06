/*
 * Copyright 2016 The BigDL Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intel.analytics.bigdl.models.rnn

import com.intel.analytics.bigdl._
import com.intel.analytics.bigdl.dataset.{DataSet, SampleToBatch}
import com.intel.analytics.bigdl.dataset.text.LabeledSentenceToSample
import com.intel.analytics.bigdl.dataset.text._
import com.intel.analytics.bigdl.dataset.text.utils.SentenceToken
import com.intel.analytics.bigdl.nn.{CrossEntropyCriterion, Module, TimeDistributedCriterion}
import com.intel.analytics.bigdl.optim._
import com.intel.analytics.bigdl.tensor.Tensor
import com.intel.analytics.bigdl.utils.{Engine, T}
import com.intel.analytics.bigdl.tensor.TensorNumericMath.TensorNumeric._
import org.apache.log4j.{Level, Logger}
import org.apache.spark.SparkContext

object Train {
  Logger.getLogger("org").setLevel(Level.ERROR)
  Logger.getLogger("akka").setLevel(Level.ERROR)
  Logger.getLogger("breeze").setLevel(Level.ERROR)
  Logger.getLogger("com.intel.analytics.bigdl.optim").setLevel(Level.INFO)
  import Utils._
  val logger = Logger.getLogger(getClass)
  def main(args: Array[String]): Unit = {
    trainParser.parse(args, new TrainParams()).map(param => {

      val conf = Engine.createSparkConf()
        .setAppName("Train rnn on text")
        .set("spark.task.maxFailures", "1")
      val sc = new SparkContext(conf)
      Engine.init

      val tokens = SequencePreprocess(
        param.dataFolder + "/train.txt",
        sc = sc,
        param.sentFile,
        param.tokenFile)

      val dictionary = Dictionary(tokens.toDistributed().data(false), param.vocabSize)
      dictionary.save(param.saveFolder)

      val maxTrainLength = tokens.toDistributed().data(false).map(x => x.length).max

      val valtokens = SequencePreprocess(
        param.dataFolder + "/val.txt",
        sc = sc,
        param.sentFile,
        param.tokenFile
      )
      val maxValLength = valtokens.toDistributed().data(false).map(x => x.length).max

      logger.info(s"maxTrain length = ${maxTrainLength}, maxVal = ${maxValLength}")

      val totalVocabLength = dictionary.getVocabSize() + 1
      val startIdx = dictionary.getIndex(SentenceToken.start)
      val endIdx = dictionary.getIndex(SentenceToken.end)
      val padFeature = Tensor[Float]().resize(totalVocabLength)
      padFeature.setValue(endIdx + 1, 1.0f)
      val padLabel = startIdx

      val trainSet = tokens
        .transform(TextToLabeledSentence[Float](dictionary))
        .transform(LabeledSentenceToSample[Float](totalVocabLength))
        .transform(SampleToBatch[Float](batchSize = param.batchSize,
          featurePadding = Some(padFeature),
          labelPadding = Some(padLabel),
          fixedLength = Some(maxTrainLength)))

      val validationSet = valtokens
        .transform(TextToLabeledSentence[Float](dictionary))
        .transform(LabeledSentenceToSample[Float](totalVocabLength))
        .transform(SampleToBatch[Float](batchSize = param.batchSize,
          featurePadding = Some(padFeature),
          labelPadding = Some(padLabel),
          fixedLength = Some(maxValLength)))

      val model = if (param.modelSnapshot.isDefined) {
        Module.load[Float](param.modelSnapshot.get)
      } else {
        val curModel = SimpleRNN(
          inputSize = totalVocabLength,
          hiddenSize = param.hiddenSize,
          outputSize = totalVocabLength)
        curModel.reset()
        curModel
      }

      val state = if (param.stateSnapshot.isDefined) {
        T.load(param.stateSnapshot.get)
      } else {
        T("learningRate" -> param.learningRate,
          "momentum" -> param.momentum,
          "weightDecay" -> param.weightDecay,
          "dampening" -> param.dampening)
      }

      val optimizer = Optimizer(
        model = model,
        dataset = trainSet,
        criterion = TimeDistributedCriterion[Float](
          CrossEntropyCriterion[Float](), sizeAverage = true)
      )

      if (param.checkpoint.isDefined) {
        optimizer.setCheckpoint(param.checkpoint.get, Trigger.everyEpoch)
      }

      if(param.overWriteCheckpoint) {
        optimizer.overWriteCheckpoint()
      }

      optimizer
        .setValidation(Trigger.everyEpoch, validationSet, Array(new Loss[Float](
          TimeDistributedCriterion[Float](CrossEntropyCriterion[Float](), sizeAverage = true))))
        .setState(state)
        .setEndWhen(Trigger.maxEpoch(param.nEpochs))
        .optimize()
    })
  }
}
