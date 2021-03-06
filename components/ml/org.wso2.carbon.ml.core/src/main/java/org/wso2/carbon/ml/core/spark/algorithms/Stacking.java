package org.wso2.carbon.ml.core.spark.algorithms;

import com.google.common.primitives.Doubles;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.mllib.classification.ClassificationModel;
import org.apache.spark.mllib.linalg.Vector;
import org.apache.spark.mllib.regression.LabeledPoint;
import org.apache.spark.mllib.util.MLUtils;
import org.apache.spark.rdd.RDD;
import org.wso2.carbon.ml.commons.domain.MLModel;
import org.wso2.carbon.ml.commons.domain.Workflow;
import org.wso2.carbon.ml.core.exceptions.MLModelBuilderException;
import org.wso2.carbon.ml.core.exceptions.MLModelHandlerException;
import org.wso2.carbon.ml.core.impl.Predictor;
import org.wso2.carbon.ml.core.internal.MLModelConfigurationContext;
import org.wso2.carbon.ml.core.utils.Util;
import scala.Tuple2;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/**
 * Implements Stacking ensemble method.
 */
public class Stacking implements Serializable, ClassificationModel {

    private static final long serialVersionUID = 3766705804606553680L;
    private MLModel metaModel;
    private List<MLModel> baseModelsList = new ArrayList<MLModel>();


    /**
     * This method trains base-learners on cross-validated trainingData
     * and combines their predictions to form another data-set(levelOneDataset)
     * which in turn is used as training-data to a meta-learner.
     * The method also trains each base-learner on whole training data.
     *
     * @param sparkContext        JavaSparkContext initialized with the application
     * @param modelId             Model ID
     * @param trainingData        Training data-set as a JavaRDD of labeled points
     * @param baseModels          List of base-learners selected for ensemble
     * @param metaAlgorithm       Name of Meta learner
     * @param paramsMetaAlgorithm Hyper-parameters of Meta learner
     * @param numFolds            Number of folds for cross-validation
     * @param seed                seed
     * @return
     */

    public void train(MLModelConfigurationContext context, JavaSparkContext sparkContext, Workflow workflow, long modelId, JavaRDD<LabeledPoint> trainingData, List<String> baseModels,
                      List<Map<String, String>> paramsBaseAlgorithms, String metaAlgorithm,
                      Map<String, String> paramsMetaAlgorithm,
                      Integer numFolds, Integer seed) throws NullPointerException, MLModelHandlerException,
            MLModelBuilderException {
        Util convert = new Util();
        BaseModelsBuilder build = new BaseModelsBuilder();
        RDD<LabeledPoint> r = trainingData.rdd();
        Tuple2<RDD<LabeledPoint>, RDD<LabeledPoint>>[] folds;

        // create folds for cross-validating data
        folds = MLUtils.kFold(r, numFolds, seed, trainingData.classTag());
        double[][] matrix = new double[(int) trainingData.count()][baseModels.size()];

        // counter for the number of base-models
        int cnt = 0;
        for (String model : baseModels) {
            // index of current data-point
            int idx = 0;
            // train base-learners on whole Dataset
            MLModel wholeBaseModel = build.buildBaseModels(context, workflow, model, trainingData, paramsBaseAlgorithms.get(cnt),
                    false);
            // train base-learners on cross-validated Dataset 
            for (Tuple2<RDD<LabeledPoint>, RDD<LabeledPoint>> fold : folds) {
                MLModel baseModel = new MLModel();
                List<String[]> dataTobePredicted = convert.labeledpointToListStringArray(fold._2().toJavaRDD());
                baseModel = build.buildBaseModels(context, workflow, model, fold._1.toJavaRDD(), paramsBaseAlgorithms.get(cnt),
                        false);
                Predictor predictor = new Predictor(modelId, baseModel, dataTobePredicted, 0.0, true, true);
                List<?> predictions = predictor.predict();
                // popluate matrix with base-learners predictions
                for (int i = 0; i < predictions.size(); i++) {
                    matrix[idx][cnt] = Double.valueOf(predictions.get(i).toString());
                    idx++;
                }
            }
            cnt++;
            // get list of base-models trained on whole Dataset
            baseModelsList.add(wholeBaseModel);
        }

        // combine predictions of each base-learner to Level-One-Dataset
        List<LabeledPoint> levelOneDataset = convert.matrixtoLabeledPoint(matrix, convert.getLabelsFolds(folds,
                (int) trainingData.count()));

        JavaRDD<LabeledPoint> levelOneDistData = sparkContext.parallelize(levelOneDataset);
        // Train Meta Learner  using Level-One-Dataset
        metaModel = build.buildBaseModels(context, workflow, metaAlgorithm, levelOneDistData, paramsMetaAlgorithm,
                true);
    }

    /**
     * This method gets a list of baseModels and predicts on the levelOneData to get levelOneTestData
     * and uses the metaModel to predict on this levelOneTestData to get final predictions.
     *
     * @param sparkContext JavaSparkContext initialized with the application
     * @param modelId      Model ID
     * @param testingData  Training data-set as a JavaRDD of labeled points
     * @return JavaPairRDD of predicted labels and actual labels
     * @throws MLModelHandlerException
     */
    public JavaPairRDD<Double, Double> test(JavaSparkContext sparkContext, long modelId, JavaRDD<LabeledPoint> testingData)
            throws MLModelHandlerException {


        Util convert = new Util();
        double[][] matrix = new double[(int) testingData.count()][baseModelsList.size()];

        // Predict on testingData to get levelOneTestData
        List<String[]> dataTobePredicted = convert.labeledpointToListStringArray(testingData);
        // counter for number of base-models
        int cnt = 0;
        for (MLModel model : baseModelsList) {
            // index of current data-point
            int idx = 0;
            Predictor predictor = new Predictor(modelId, model, dataTobePredicted, 0.0, true, true);
            List<?> predictions = predictor.predict();

            for (int i = 0; i < predictions.size(); i++) {
                matrix[idx][cnt] = Double.valueOf(predictions.get(i).toString());
                idx++;
            }
            cnt++;
        }
        // convert matrix to list of string array
        List<LabeledPoint> levelOneTestDataset = convert.matrixtoLabeledPoint(matrix, convert.getLabels(testingData));
        List<String[]> LevelOneTestDatasetList = convert.labeledpointToStringArray(levelOneTestDataset);

        Predictor predictor = new Predictor(modelId, metaModel, LevelOneTestDatasetList, 0.0, true, true);
        List<?> levelOnePredictions = predictor.predict();
        List<Double> labelsList = Doubles.asList(convert.getLabels(testingData));
        // create a list of predictions and Labels pairs
        List<Tuple2<Double, Double>> list = new ArrayList<Tuple2<Double, Double>>();
        for (int j = 0; j < levelOnePredictions.size(); j++) {
            list.add(new Tuple2<Double, Double>(Double.valueOf(levelOnePredictions.get(j).toString()), labelsList.get(j)));

        }
        return sparkContext.parallelizePairs(list);

    }

    // methods implemented since this class inherits interface class:ClassificationModel
    @Override
    public RDD<Object> predict(RDD<Vector> rdd) {return null;}

    @Override
    public double predict(Vector vector) {
        return 0;
    }

    @Override
    public JavaRDD<Double> predict(JavaRDD<Vector> javaRDD) {
        return null;
    }
}


