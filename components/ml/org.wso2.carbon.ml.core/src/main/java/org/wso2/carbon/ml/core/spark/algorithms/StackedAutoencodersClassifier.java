/*
 * Copyright (c) 2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.ml.core.spark.algorithms;

import org.wso2.carbon.ml.core.spark.models.SparkDeeplearningModel;
import hex.FrameSplitter;
import hex.deeplearning.DeepLearning;
import hex.deeplearning.DeepLearningModel;
import hex.deeplearning.DeepLearningParameters;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.mllib.regression.LabeledPoint;
import scala.Tuple2;
import java.io.Serializable;
import java.util.ArrayList;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.spark.api.java.JavaSparkContext;
import water.DKV;
import water.H2O;
import water.Key;
import water.Scope;
import water.fvec.Frame;
import static water.util.FrameUtils.generateNumKeys;
import water.util.MRUtils;

public class StackedAutoencodersClassifier implements Serializable {

    private static final Log log = LogFactory.getLog(StackedAutoencodersClassifier.class);

    private transient DeepLearning dl;
    private transient DeepLearningModel model;


    /**
     *
     *
     * @param trainingData Training dataset as a JavaRDD of labeled points
     * @param lambda Lambda parameter
     * @return Naive bayes model
     */
    /**
     * This method trains a stacked autoencoder
     *
     * @param trainData Training dataset as a JavaRDD
     * @param batchSize Size of a training mini-batch
     * @param layerSizes Number of neurons for each layer
     * @param epochs Number of epochs to train
     * @param trainFraction The fraction considered for training
     * @param responseColumn Name of the response column
     * @param modelID Id of the model
     * @return
     */
    public SparkDeeplearningModel train(JavaRDD<LabeledPoint> trainData, int batchSize,
            int[] layerSizes, int epochs, double trainFraction, String responseColumn, long modelID) {
        //build stacked autoencoder by training the model with training data                               

        SparkDeeplearningModel sparkDeeplearningModel = new SparkDeeplearningModel();

        try {
            Scope.enter();
            if (trainData != null) {
                
                Frame frame = DeeplearningModelUtils.JavaRDDtoFrame(trainData);
                Frame shufFrame = MRUtils.shuffleFramePerChunk(frame, 999999999);

                //H2O uses default C<x> for column header
                String classifColName = "C" + frame.numCols();

                //splitting train file to train, validation and test
                //anything other than 0.5f gives a wierd exception
                //barrier onExCompletion for hex.deeplearning.DeepLearning$DeepLearningDriver@78ec854
                double[] ratios = new double[]{trainFraction};
                FrameSplitter fs = new FrameSplitter(shufFrame, ratios, generateNumKeys(shufFrame._key, ratios.length + 1), null);
                H2O.submitTask(fs).join();
                Frame[] splits = fs.getResult();

                Frame trainFrame = splits[0];
                Frame vframe = splits[1];

                log.info("Creating Deeplearning parameters");
                DeepLearningParameters p = new DeepLearningParameters();

                // populate model parameters
                p._model_id = Key.make("dl_" + modelID + "_model");
                p._train = trainFrame._key;
                p._valid = vframe._key;
                p._response_column = classifColName; // last column is the response
                //This is causin all the predictions to be 0.0
                //p._autoencoder = true;
                p._activation = DeepLearningParameters.Activation.RectifierWithDropout;
                p._hidden = layerSizes;
                p._train_samples_per_iteration = batchSize;
                p._input_dropout_ratio = 0.1;
                p._l1 = 1e-5;
                p._max_w2 = 10;
                p._epochs = epochs;

                // Convert response to categorical (digits 1 to <num of columns>)
                int ci = trainFrame.find(classifColName);
                Scope.track(trainFrame.replace(ci, trainFrame.vecs()[ci].toEnum())._key);
                Scope.track(vframe.replace(ci, vframe.vecs()[ci].toEnum())._key);
                DKV.put(trainFrame);
                DKV.put(vframe);

                // speed up training
                p._adaptive_rate = true; //disable adaptive per-weight learning rate -> default settings for learning rate and momentum are probably not ideal (slow convergence)
                p._replicate_training_data = true; //avoid extra communication cost upfront, got enough data on each node for load balancing
                p._overwrite_with_best_model = true; //no need to keep the best model around
                p._diagnostics = false; //no need to compute statistics during training
                p._classification_stop = -1;
                p._score_interval = 60; //score and print progress report (only) every 20 seconds
                p._score_training_samples = batchSize; //only score on a small sample of the training set -> don't want to spend too much time scoring (note: there will be at least 1 row per chunk)

                dl = new DeepLearning(p);
                log.info("Start training deeplearning model ....");
                try {
                    model = dl.trainModel().get();
                    sparkDeeplearningModel.setDeepLearningModel(model);

                    log.info("Successfully finished Training deeplearning model ....");
                } catch (RuntimeException ex) {
                    log.info("Error in training the model");
                    log.info(ex.getMessage());
                }
            } else {
                log.error("Train file not found!");
            }
        } catch (RuntimeException ex) {
            log.info("Failed to train the deeplearning model [id] " + modelID + ". " + ex.getMessage());
        } finally {
            Scope.exit();
        }

        return sparkDeeplearningModel;
    }

    

    /**
     * This method applies a stacked autoencoders model to a given dataset and
     * make predictions
     *
     * @param ctxt JavaSparkContext
     * @param saeModel Stacked Autoencoders model
     * @param test Testing dataset as a JavaRDD of labeled points
     * @return
     */
    public JavaPairRDD<Double, Double> test(JavaSparkContext ctxt, final SparkDeeplearningModel saeModel, JavaRDD<LabeledPoint> test) {

        Scope.enter();
        
        Frame testData = DeeplearningModelUtils.JavaRDDtoFrame(test);
        Frame testDataWithoutLabels = testData.subframe(0,testData.numCols()-1);
        double[] predVales = saeModel.predict(testDataWithoutLabels);
        double[] labels = testData.vec(testData.numCols()-1).toDoubleArray();
        
        Scope.exit();

        ArrayList<Tuple2<Double, Double>> tupleList = new ArrayList<Tuple2<Double, Double>>();
        for (int i=0; i<labels.length;i++) {            
            tupleList.add(new Tuple2<Double, Double>(predVales[i], labels[i]));            
        }        

        return ctxt.parallelizePairs(tupleList);

    }

}
