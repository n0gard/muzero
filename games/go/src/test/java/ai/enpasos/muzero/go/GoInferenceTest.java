package ai.enpasos.muzero.go;

import ai.enpasos.muzero.go.config.GoConfigFactory;
import ai.enpasos.muzero.platform.agent.Inference;
import org.testng.annotations.Ignore;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

public class GoInferenceTest {

    @Test
    @Ignore
    void aiDecisionGoFast() {
        List<Integer> actions = new ArrayList<>();
        int nextMoveInt = Inference.aiDecision(actions, false, "./pretrained", GoConfigFactory.getGoInstance(5));
    }


    @Test
    @Ignore
    void aiDecisionGoSlow() {
        List<Integer> actions = new ArrayList<>();
        int nextMoveInt = Inference.aiDecision(actions, true, "./pretrained", GoConfigFactory.getGoInstance(5));
    }


    @Test
    @Ignore
    void aiDecisionSlowLongerGame() {

        List<Integer> actions = List.of(12, 8, 13, 11, 6, 7, 16, 18, 17, 22, 10, 19, 21, 1, 14, 2, 9, 23, 24, 18, 19, 25, 23, 5, 0, 25, 3, 25);

        int nextMoveInt = Inference.aiDecision(actions, true, "./pretrained", GoConfigFactory.getGoInstance(5));

    }


}
