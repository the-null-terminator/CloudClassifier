package ml;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Random;
import java.util.zip.Adler32;
import java.util.zip.Checksum;
import org.apache.ant.compress.taskdefs.Unzip;
import org.apache.commons.io.FileUtils;
import org.datavec.api.io.filters.BalancedPathFilter;
import org.datavec.api.io.labels.ParentPathLabelGenerator;
import org.datavec.api.split.FileSplit;
import org.datavec.api.split.InputSplit;
import org.datavec.image.loader.BaseImageLoader;
import org.datavec.image.loader.NativeImageLoader;
import org.datavec.image.recordreader.ImageRecordReader;
import org.deeplearning4j.datasets.datavec.RecordReaderDataSetIterator;
import org.deeplearning4j.eval.Evaluation;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.Updater;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.conf.layers.OutputLayer.Builder;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.transferlearning.FineTuneConfiguration;
import org.deeplearning4j.nn.transferlearning.TransferLearning;
import org.deeplearning4j.nn.transferlearning.TransferLearning.GraphBuilder;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.optimize.api.IterationListener;
import org.deeplearning4j.optimize.listeners.ScoreIterationListener;
import org.deeplearning4j.util.ModelSerializer;
import org.deeplearning4j.zoo.PretrainedType;
import org.deeplearning4j.zoo.ZooModel;
import org.deeplearning4j.zoo.model.VGG16;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.dataset.api.preprocessor.VGG16ImagePreProcessor;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.nd4j.linalg.lossfunctions.LossFunctions.LossFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TrainImageNet
{
  private static final Logger LOGGER = LoggerFactory.getLogger(TrainImageNet.class);
  private static final long seed = 12345L;
  public static final Random RAND_NUM_GEN = new Random(12345L);
  public static final String[] ALLOWED_FORMATS = BaseImageLoader.ALLOWED_FORMATS;
  public static ParentPathLabelGenerator LABEL_GENERATOR_MAKER = new ParentPathLabelGenerator();
  public static BalancedPathFilter PATH_FILTER = new BalancedPathFilter(RAND_NUM_GEN, ALLOWED_FORMATS, LABEL_GENERATOR_MAKER);
  private static final int EPOCH = 5;
  private static final int BATCH_SIZE = 16;
  private static final int TRAIN_SIZE = 85;
  private static final int NUM_POSSIBLE_LABELS = 2;
  private static final int SAVING_INTERVAL = 100;
  public static String DATA_PATH = "resources";
  public static final String TRAIN_FOLDER = DATA_PATH + "/train_both";
  public static final String TEST_FOLDER = DATA_PATH + "/test_both";
  private static final String SAVING_PATH = DATA_PATH + "/saved/modelIteration_";
  private static final String FREEZE_UNTIL_LAYER = "fc2";
  private static final String DATA_URL = "";
  
  public static void unzip(File fileZip)
    throws IOException
  {
    Unzip unzipper = new Unzip();
    unzipper.setSrc(fileZip);
    unzipper.setDest(new File(DATA_PATH));
    unzipper.execute();
  }
  
  public static void main(String[] args)
    throws IOException
  {
    ZooModel zooModel = new VGG16();
    LOGGER.info("Start Downloading VGG16 model...");
    ComputationGraph preTrainedNet = (ComputationGraph)zooModel.initPretrained(PretrainedType.IMAGENET);
    LOGGER.info(preTrainedNet.summary());
    
    LOGGER.info("Start Downloading Data...");
    
    downloadAndUnzipDataForTheFirstTime();
    LOGGER.info("Data unzipped");
    
    File trainData = new File(TRAIN_FOLDER);
    File testData = new File(TEST_FOLDER);
    FileSplit train = new FileSplit(trainData, NativeImageLoader.ALLOWED_FORMATS, RAND_NUM_GEN);
    FileSplit test = new FileSplit(testData, NativeImageLoader.ALLOWED_FORMATS, RAND_NUM_GEN);
    
    InputSplit[] sample = train.sample(PATH_FILTER, new double[] { 85.0D, 15.0D });
    DataSetIterator trainIterator = getDataSetIterator(sample[0]);
    DataSetIterator devIterator = getDataSetIterator(sample[1]);
    
    FineTuneConfiguration fineTuneConf = new FineTuneConfiguration.Builder().learningRate(Double.valueOf(5.0E-5D)).optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT).updater(Updater.NESTEROVS).seed(12345L).build();
    
    ComputationGraph vgg16Transfer = new TransferLearning.GraphBuilder(preTrainedNet).fineTuneConfiguration(fineTuneConf).setFeatureExtractor(new String[] { "fc2" }).removeVertexKeepConnections("predictions").addLayer("predictions", ((OutputLayer.Builder)((OutputLayer.Builder)((OutputLayer.Builder)((OutputLayer.Builder)new OutputLayer.Builder(LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD).nIn(4096)).nOut(2)).weightInit(WeightInit.XAVIER)).activation(Activation.SOFTMAX)).build(), new String[] { "fc2" }).build();
    vgg16Transfer.setListeners(new IterationListener[] { new ScoreIterationListener(5) });
    LOGGER.info(vgg16Transfer.summary());
    
    DataSetIterator testIterator = getDataSetIterator(test.sample(PATH_FILTER, new double[] { 1.0D, 0.0D })[0]);
    int iEpoch = 0;
    int i = 0;
    while (iEpoch < 5)
    {
      while (trainIterator.hasNext())
      {
        DataSet trained = (DataSet)trainIterator.next();
        vgg16Transfer.fit(trained);
        if ((i % 100 == 0) && (i != 0))
        {
          ModelSerializer.writeModel(vgg16Transfer, new File(SAVING_PATH + i + "_epoch_" + iEpoch + ".zip"), false);
          evalOn(vgg16Transfer, devIterator, i);
        }
        i++;
      }
      trainIterator.reset();
      iEpoch++;
      
      evalOn(vgg16Transfer, testIterator, iEpoch);
    }
  }
  
  private static void downloadAndUnzipDataForTheFirstTime()
    throws IOException
  {
    File data = new File(DATA_PATH + "/data.zip");
    if ((!data.exists()) || (FileUtils.checksum(data, new Adler32()).getValue() != 1195241806L))
    {
      data.delete();
      FileUtils.copyURLToFile(new URL("https://dl.dropboxusercontent.com/s/tqnp49apphpzb40/dataTraining.zip?dl=0"), data);
      LOGGER.info("File downloaded");
    }
    if (!new File(TRAIN_FOLDER).exists())
    {
      LOGGER.info("Unzipping Data...");
      unzip(data);
    }
  }
  
  public static void evalOn(ComputationGraph vgg16Transfer, DataSetIterator testIterator, int iEpoch)
    throws IOException
  {
    LOGGER.info("Evaluate model at iteration " + iEpoch + " ....");
    Evaluation eval = vgg16Transfer.evaluate(testIterator);
    LOGGER.info(eval.stats());
    testIterator.reset();
  }
  
  public static DataSetIterator getDataSetIterator(InputSplit sample)
    throws IOException
  {
    ImageRecordReader imageRecordReader = new ImageRecordReader(224, 224, 3, LABEL_GENERATOR_MAKER);
    imageRecordReader.initialize(sample);
    
    DataSetIterator iterator = new RecordReaderDataSetIterator(imageRecordReader, 16, 1, 2);
    iterator.setPreProcessor(new VGG16ImagePreProcessor());
    return iterator;
  }
}
