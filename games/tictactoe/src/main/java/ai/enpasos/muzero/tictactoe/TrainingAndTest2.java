package ai.enpasos.muzero.tictactoe;


import ai.djl.Device;
import ai.djl.Model;
import ai.djl.metric.Metric;
import ai.djl.metric.Metrics;
import ai.djl.ndarray.types.Shape;
import ai.djl.training.DefaultTrainingConfig;
import ai.djl.training.EasyTrain;
import ai.djl.training.Trainer;
import ai.djl.training.dataset.Batch;
import ai.enpasos.muzero.platform.MuZero;
import ai.enpasos.muzero.platform.MuZeroConfig;
import ai.enpasos.muzero.platform.agent.fast.model.djl.NetworkHelper;
import ai.enpasos.muzero.platform.agent.fast.model.djl.blocks.atraining.MuZeroBlock;
import ai.enpasos.muzero.platform.agent.gamebuffer.ReplayBuffer;
import ai.enpasos.muzero.tictactoe.config.TicTacToeConfigFactory;
import ai.enpasos.muzero.tictactoe.test.TicTacToeTest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.List;

import static ai.enpasos.muzero.platform.MuZero.getNetworksBasedir;
import static ai.enpasos.muzero.platform.MuZero.train;
import static ai.enpasos.muzero.platform.agent.fast.model.djl.Helper.logNDManagers;
import static ai.enpasos.muzero.platform.agent.fast.model.djl.NetworkHelper.*;

@Slf4j
public class TrainingAndTest2 {

    public static void main(String[] args) throws URISyntaxException, IOException {
        MuZeroConfig config = TicTacToeConfigFactory.getTicTacToeInstance();

        String dir = "./memory/";
        config.setOutputDir(dir);

        boolean freshBuffer = false;
        int numberOfEpochs = 1;

        try (Model model = Model.newInstance(config.getModelName(), Device.gpu())) {


            FileUtils.deleteDirectory(new File(dir));
            MuZero.createNetworkModelIfNotExisting(config);


            ReplayBuffer replayBuffer = new ReplayBuffer(config);
            if (freshBuffer) {
                while (!replayBuffer.getBuffer().isBufferFilled()) {
                    MuZero.playOnDeepThinking(model, config, replayBuffer);
                    replayBuffer.saveState();
                }
            } else {
                replayBuffer.loadLatestState();
                MuZero.initialFillingBuffer(model, config, replayBuffer);
            }

            int trainingStep = NetworkHelper.numberOfLastTrainingStep(config);

            while (trainingStep < config.getNumberOfTrainingSteps()) {
                if (trainingStep != 0) {
                    log.info("last training step = {}", trainingStep);
                    log.info("numSimulations: " + config.getNumSimulations());
                    MuZero.playOnDeepThinking(model, config, replayBuffer);
                    replayBuffer.saveState();
                }
                int numberOfTrainingStepsPerEpoch = config.getNumberOfTrainingStepsPerEpoch();
                int epoch = 0;
                boolean withSymmetryEnrichment = true;
                MuZeroBlock block = new MuZeroBlock(config);


                logNDManagers(model.getNDManager());

                model.setBlock(block);

                try {
                    model.load(Paths.get(getNetworksBasedir(config)));
                } catch (Exception e) {
                    log.info("*** no existing model has been found ***");
                }

                String prop = model.getProperty("Epoch");
                if (prop != null) {
                    epoch = Integer.parseInt(prop);
                }

                DefaultTrainingConfig djlConfig = setupTrainingConfig(config, epoch);

                //  float gradientScale = 1f / config.getNumUnrollSteps();

                try (Trainer trainer = model.newTrainer(djlConfig)) {
                    trainer.setMetrics(new Metrics());
                    Shape[] inputShapes = getInputShapes(config);
                    trainer.initialize(inputShapes);

                    for (int i = 0; i < numberOfEpochs; i++) {
                        for (int m = 0; m < numberOfTrainingStepsPerEpoch; m++) {
                            try (Batch batch = getBatch(config, model.getNDManager(), replayBuffer, withSymmetryEnrichment)) {
                                log.debug("trainBatch " + m);
                                EasyTrain.trainBatch(trainer, batch);
                                trainer.step();
                            }
                        }
                        Metrics metrics = trainer.getMetrics();

                        // mean loss
                        List<Metric> ms = metrics.getMetric("train_all_CompositeLoss");
                        Double meanLoss = ms.stream().mapToDouble(m -> m.getValue().doubleValue()).average().getAsDouble();
                        model.setProperty("MeanLoss", meanLoss.toString());
                        log.info("MeanLoss: " + meanLoss.toString());

                        //


                        // mean value loss
                        Double meanValueLoss = metrics.getMetricNames().stream()
                                .filter(name -> name.startsWith("train_all") && name.contains("value_0"))
                                .mapToDouble(name -> metrics.getMetric(name).stream().mapToDouble(m -> m.getValue().doubleValue()).average().getAsDouble())
                                .sum();
                        meanValueLoss += metrics.getMetricNames().stream()
                                .filter(name -> name.startsWith("train_all") && !name.contains("value_0") && name.contains("value"))
                                .mapToDouble(name -> metrics.getMetric(name).stream().mapToDouble(m -> m.getValue().doubleValue()).average().getAsDouble())
                                .sum();
                        model.setProperty("MeanValueLoss", meanValueLoss.toString());
                        log.info("MeanValueLoss: " + meanValueLoss.toString());

                        // mean policy loss
                        Double meanPolicyLoss = metrics.getMetricNames().stream()
                                .filter(name -> name.startsWith("train_all") && name.contains("policy_0"))
                                .mapToDouble(name -> metrics.getMetric(name).stream().mapToDouble(m -> m.getValue().doubleValue()).average().getAsDouble())
                                .sum();
                        meanPolicyLoss += metrics.getMetricNames().stream()
                                .filter(name -> name.startsWith("train_all") && !name.contains("policy_0") && name.contains("policy"))
                                .mapToDouble(name -> metrics.getMetric(name).stream().mapToDouble(m -> m.getValue().doubleValue()).average().getAsDouble())
                                .sum();
                        model.setProperty("MeanPolicyLoss", meanPolicyLoss.toString());
                        log.info("MeanPolicyLoss: " + meanPolicyLoss.toString());


                        trainer.notifyListeners(listener -> listener.onEpoch(trainer));
                    }

                    logNDManagers(trainer.getManager());

                }
                trainingStep = epoch * numberOfTrainingStepsPerEpoch;

            }

            boolean passed = TicTacToeTest.test(config);
            String message = "INTEGRATIONTEST = " + (passed ? "passed" : "failed");
            log.info(message);
            if (!passed) throw new RuntimeException(message);
        }
    }


}
