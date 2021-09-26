package ai.enpasos.muzero.debug;

import ai.djl.Device;
import ai.djl.Model;
import ai.enpasos.muzero.MuZeroConfig;
import ai.enpasos.muzero.agent.fast.model.djl.blocks.atraining.MuZeroBlock;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.File;
import java.io.StringWriter;
import java.nio.file.Paths;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.IntStream;

import static ai.enpasos.muzero.MuZero.getNetworksBasedir;
import static ai.enpasos.muzero.agent.fast.model.Network.*;

public class LossExtractor {

    public static void main(String[] args) throws Exception {
        MuZeroConfig config = MuZeroConfig.getGoInstance(5);
      //     MuZeroConfig config = MuZeroConfig.getTicTacToeInstance();
        MuZeroBlock block = new MuZeroBlock(config);

        StringWriter stringWriter = new StringWriter();


       try( CSVPrinter csvPrinter = new CSVPrinter(stringWriter, CSVFormat.EXCEL.withDelimiter(';').withHeader("trainingStep", "totalLoss", "valueLoss", "policyLoss"))) {


           try (Model model = Model.newInstance(config.getModelName(), Device.gpu()))
           {
               model.setBlock(block);
               IntStream.range(1,300).forEach(
                       i -> {
                           try {
                               model.load(Paths.get(getNetworksBasedir(config)), model.getName(), Map.of("epoch", i));
                               int epoch = getEpoch(model);
                               csvPrinter.printRecord(epoch,
                                       NumberFormat.getNumberInstance().format(getDoubleValue(model, "MeanLoss")),
                                       NumberFormat.getNumberInstance().format(getDoubleValue(model, "MeanValueLoss")),
                                       NumberFormat.getNumberInstance().format(getDoubleValue(model, "MeanPolicyLoss"))
                               );
                           } catch (Exception e) {
                           }
                       }
               );
           }
       }

        System.out.println(stringWriter.toString());
    }
}
