/*
 *  Copyright (c) 2021 enpasos GmbH
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package ai.enpasos.muzero.platform.agent.memorize;

import ai.enpasos.muzero.platform.agent.intuitive.NetworkIO;
import ai.enpasos.muzero.platform.agent.intuitive.Observation;
import ai.enpasos.muzero.platform.agent.intuitive.djl.MyL2Loss;
import ai.enpasos.muzero.platform.agent.rational.Action;
import ai.enpasos.muzero.platform.agent.rational.ActionHistory;
import ai.enpasos.muzero.platform.agent.rational.GumbelSearch;
import ai.enpasos.muzero.platform.agent.rational.Node;
import ai.enpasos.muzero.platform.agent.rational.Player;
import ai.enpasos.muzero.platform.common.MuZeroException;
import ai.enpasos.muzero.platform.config.MuZeroConfig;
import ai.enpasos.muzero.platform.config.PlayTypeKey;
import ai.enpasos.muzero.platform.config.PlayerMode;
import ai.enpasos.muzero.platform.environment.Environment;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import static ai.enpasos.muzero.platform.common.ProductPathMax.getProductPathMax;


/**
 * A single episode of interaction with the environment.
 */
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Slf4j
public abstract class Game {


    protected boolean purelyRandom;
    @EqualsAndHashCode.Include
    protected GameDTO gameDTO;
    protected MuZeroConfig config;
    protected int actionSpaceSize;
    protected double discount;
    protected Environment environment;
    protected GameDTO originalGameDTO;
    //   @Builder.Default
    List<Double> valueImprovements = new ArrayList<>();
    boolean playedMoreThanOnce;
    double surpriseMean;
    double surpriseMax;

    boolean done;

    int epoch;


    GumbelSearch searchManager;
    private Random r;
    private float error;
    private boolean debug;

    private boolean actionApplied;

    private PlayTypeKey playTypeKey;



    protected Game(@NotNull MuZeroConfig config) {
        this.config = config;
        this.gameDTO = new GameDTO();
        this.actionSpaceSize = config.getActionSpaceSize();
        this.discount = config.getDiscount();

        r = new Random();
    }

    protected Game(@NotNull MuZeroConfig config, GameDTO gameDTO) {
        this.config = config;
        this.gameDTO = gameDTO;
        this.actionSpaceSize = config.getActionSpaceSize();
        this.discount = config.getDiscount();
    }

    public static Game decode(@NotNull MuZeroConfig config, byte @NotNull [] bytes) {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes);
        try (ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream)) {
            GameDTO dto = (GameDTO) objectInputStream.readObject();
            Game game = config.newGame();
            Objects.requireNonNull(game).setGameDTO(dto);
            return game;
        } catch (Exception e) {
            throw new MuZeroException(e);
        }
    }

    public float calculateSquaredDistanceBetweenOriginalAndCurrentValue() {
        this.error = 0;
        for (int i = 0; i < this.originalGameDTO.getRootValuesFromInitialInference().size(); i++) {
            double d = this.originalGameDTO.getRootValuesFromInitialInference().get(i)
                - this.getGameDTO().getRootValuesFromInitialInference().get(i);
            this.error += d * d;
        }
        return this.error;
    }

    public @NotNull Game copy() {
        Game copy = getConfig().newGame();
        copy.setGameDTO(this.gameDTO.copy());
        copy.setConfig(this.getConfig());
        copy.setDiscount(this.getDiscount());
        copy.setActionSpaceSize(this.getActionSpaceSize());
        copy.replayToPosition(copy.getGameDTO().getActions().size());
        return copy;
    }


    public @NotNull Game copy(int numberOfActions) {
        Game copy = getConfig().newGame();
        copy.setGameDTO(this.gameDTO.copy(numberOfActions));
        copy.setConfig(this.getConfig());
        copy.setDiscount(this.getDiscount());
        copy.setActionSpaceSize(this.getActionSpaceSize());
        copy.replayToPosition(copy.getGameDTO().getActions().size());
        return copy;
    }


    public void checkAssumptions() {
        assertTrue(this.getGameDTO().getPolicyTargets().size() == this.getGameDTO().getActions().size(), "policyTargets.size() == actions.size()");
    }

    protected void assertTrue(boolean b, String s) {
        if (b) return;
        log.error(s);
        throw new MuZeroException("assertion violated: " + s);
    }

    public @Nullable Float getLastReward() {
        if (getGameDTO().getRewards().size() == 0) return null;
        return getGameDTO().getRewards().get(getGameDTO().getRewards().size() - 1);
    }

    public abstract boolean terminal();

    public abstract List<Action> legalActions();

    public abstract List<Action> allActionsInActionSpace();

    public void apply(int @NotNull ... actionIndex) {
        Arrays.stream(actionIndex).forEach(
            i -> apply(config.newAction(i))
        );
    }

    public void apply(List<Integer> actions) {
        actions.forEach(
            i -> apply(config.newAction(i))
        );
    }

    public void apply(@NotNull Action action) {
        float reward = this.environment.step(action);

        this.getGameDTO().getRewards().add(reward);
        this.getGameDTO().getActions().add(action.getIndex());
    }

    public List<Target> makeTarget(int stateIndex, int numUnrollSteps ) {
        List<Target> targets = new ArrayList<>();

        IntStream.range(stateIndex, stateIndex + numUnrollSteps + 1).forEach(currentIndex -> {
            Target target = new Target();
            fillTarget(currentIndex, target );
            targets.add(target);
        });
        return targets;
    }

    @SuppressWarnings("java:S3776")
    private void fillTarget( int currentIndex, Target target) {
        double value;
        int tdSteps = 0;
        if (this.getPlayTypeKey() == PlayTypeKey.REANALYSE) {

            if (!config.isNetworkWithRewardHead() && currentIndex > this.getGameDTO().getRewards().size() - 1) {
                int i = this.getGameDTO().getRewards().size() - 1;
                value = (double) this.getGameDTO().getRewards().get(i) * Math.pow(this.discount, i) * getPerspective(i - currentIndex);
            } else {
                // TODO tdSteps

                value = this.getGameDTO().getRootValueTargets().get( this.getGameDTO().getRootValueTargets().size()-1);
            }
        } else {
            if (gameDTO.isHybrid() && currentIndex < this.getGameDTO().getTHybrid()) {
                int T = this.getGameDTO().getRewards().size() - 1;
                tdSteps = getTdSteps(   currentIndex, T);
            } else {
                tdSteps = this.getGameDTO().getTdSteps();
            }
            // TODO is TDSteps == T handled correctly?
             value = calculateValue(tdSteps, currentIndex);
        }


        float lastReward = getLastReward(currentIndex);

        if (currentIndex < this.getGameDTO().getPolicyTargets().size()) {

            setValueOnTarget(target, value);
            target.setReward(lastReward);
            if (gameDTO.isHybrid() && tdSteps == 0 && !config.isForTdStep0PolicyTraining()) {
                target.setPolicy(new float[this.actionSpaceSize]);
                // the idea is not to put any force on the network to learn a particular action where it is not necessary
                Arrays.fill(target.getPolicy(), 0f);
            } else {
                target.setPolicy(this.getGameDTO().getPolicyTargets().get(currentIndex));
            }
        } else if (!config.isNetworkWithRewardHead() && currentIndex == this.getGameDTO().getPolicyTargets().size()) {
            // If we do not train the reward (as only boardgames are treated here)
            // the value has to take the role of the reward on this node (needed in MCTS)
            // if we were running the network with reward head
            // the value would be 0 here
            // but as we do not get the expected reward from the network
            // we need use this node to keep the reward value
            // therefore target.value is not 0f
            // To make the whole thing clear. The cases with and without a reward head should be treated in a clearer separation

            setValueOnTarget(target, value); // this is not really the value, it is taking the role of the reward here
            target.setReward(lastReward);
            target.setPolicy(new float[this.actionSpaceSize]);
            // the idea is not to put any force on the network to learn a particular action where it is not necessary
            Arrays.fill(target.getPolicy(), 0f);
        } else {
            setValueOnTarget(target, (float) value);
            target.setReward(lastReward);
            target.setPolicy(new float[this.actionSpaceSize]);
            // the idea is not to put any force on the network to learn a particular action where it is not necessary
            Arrays.fill(target.getPolicy(), 0f);
        }

    }

    public int getTdSteps(int currentIndex, int T) {
        if (!config.offPolicyCorrectionOn()) return 0;
        if (this.getGameDTO().getPlayoutPolicy() == null) return 0;
        double b = ThreadLocalRandom.current().nextDouble(0, 1);
        int tdSteps =  getTdSteps(b, currentIndex, T);

        return tdSteps;
    }

   public int getTdSteps(double b, int currentIndex, int T) {
        double localPRatioMax = Math.min(this.pRatioMax, config.getOffPolicyRatioLimit());

        int tdSteps;
        tdSteps = 0;

        if (currentIndex >= T) return 0;


        for (int t = T; t >= currentIndex; t--) {

            double pBase = 1;
            for (int i = currentIndex; i < t; i++) {
                pBase *= this.getGameDTO().getPlayoutPolicy().get(i)[this.getGameDTO().getActions().get(i)];
            }
            double p = 1;
            for (int i = currentIndex; i < t; i++) {
                p *= this.getGameDTO().getPolicyTargets().get(i)[this.getGameDTO().getActions().get(i)];
            }
            double pRatio = p / pBase;
            //System.out.println((t - currentIndex) + "; " + pRatio);
            if (pRatio > b*localPRatioMax) {
                tdSteps = t - currentIndex;
                return tdSteps;
            }

        }
        throw new MuZeroNoSampleMatch();
    }

    private void setValueOnTarget(Target target, double value) {
        target.setValue((float) value);
    }

    private float getLastReward(int currentIndex) {
        float lastReward;
        if (currentIndex > 0 && currentIndex <= this.getGameDTO().getRewards().size()) {
            lastReward = this.getGameDTO().getRewards().get(currentIndex - 1);
        } else {
            lastReward = 0f;
        }
        return lastReward;
    }


    private double calculateValue(int tdSteps, int currentIndex) {

        double value = getBootstrapValue(currentIndex, tdSteps);

        value = addValueFromReward(currentIndex, tdSteps, value);


        // TODO repair the handling
//        if (this.getGameDTO().getRewards().size() - 1 == currentIndex + tdSteps) {
//            int bootstrapI = currentIndex + tdSteps;
//            value = (double) this.getGameDTO().getRewards().get(bootstrapI) * Math.pow(this.discount, bootstrapI) * getPerspective(tdSteps);
//        }


//        if (gameDTO.isHybrid() && tdSteps == 0) {
//            if (currentIndex < this.getGameDTO().getRootValuesFromInitialInference().size()) {
//                value = this.getGameDTO().getRootValuesFromInitialInference().get(currentIndex);
//            } else if (this.getGameDTO().getRootValuesFromInitialInference().size() == 0) {
//                // this should not happen, but on random initialization
//            } else {
//                log.debug("value = MyL2Loss.NULL_VALUE;");
//                value = MyL2Loss.NULL_VALUE;  // no value change force
//            }
//        }
      //  if (gameDTO.isHybrid() && currentIndex < this.getGameDTO().getTHybrid()) {
//            if (currentIndex >= this.getGameDTO().getRootValueTargets().size()) {
//              //  value = this.getGameDTO().getRootValuesFromInitialInference().get(currentIndex);
//
//            if (currentIndex < this.getGameDTO().getRootValueTargets().size()) {
//                value = this.getGameDTO().getRootValueTargets().get(currentIndex);
//            }
////            } else if (this.getGameDTO().getRootValuesFromInitialInference().size() == 0) {
////                value = calculateValueFromReward(currentIndex, bootstrapIndex, value); // this should not happen, only on random initialization
//          //  } else {
          //  value = MyL2Loss.NULL_VALUE;  // no value change force
//                value = calculateValueFromReward(currentIndex, bootstrapIndex, value);
//            }
//        } else {
//            value = calculateValueFromReward(currentIndex, bootstrapIndex, value);
      //  }
        return value;

    }


    private double addValueFromReward(int currentIndex, int tdSteps, double value) {
        int bootstrapIndex = currentIndex + tdSteps;
        if (currentIndex > this.getGameDTO().getRewards().size() - 1) {
            int i = this.getGameDTO().getRewards().size() - 1;
            value += (double) this.getGameDTO().getRewards().get(i) * Math.pow(this.discount, i) * getPerspective(i - currentIndex);
        } else {
            for (int i = currentIndex; i < this.getGameDTO().getRewards().size() && i < bootstrapIndex; i++) {
                value += (double) this.getGameDTO().getRewards().get(i) * Math.pow(this.discount, i) * getPerspective(i - currentIndex);
            }
        }
        return value;
    }

    private double getPerspective(int delta) {
        boolean perspectiveChange = config.getPlayerMode() == PlayerMode.TWO_PLAYERS;
        double perspective = 1.0;
        if (perspectiveChange) {
            perspective = Math.pow(-1, delta);
        }
        return perspective;
    }

    private double getBootstrapValue(int currentIndex, int tdSteps) {
        int bootstrapIndex = currentIndex + tdSteps;
        double value = 0;
        if (bootstrapIndex < this.getGameDTO().getRootValueTargets().size()) {
            if (gameDTO.isHybrid()) {
                if (currentIndex < this.getGameDTO().getRootValuesFromInitialInference().size()) {
                    value = this.getGameDTO().getRootValuesFromInitialInference().get(bootstrapIndex) * Math.pow(this.discount, tdSteps) * getPerspective(tdSteps);
                }
            } else {
                value = this.getGameDTO().getRootValueTargets().get(bootstrapIndex) * Math.pow(this.discount, tdSteps) * getPerspective(tdSteps);
            }
        } else {
            value = 0;
        }



        return value;
    }


    public abstract Player toPlay();

    public @NotNull ActionHistory actionHistory() {
        return new ActionHistory(config, this.gameDTO.getActions());
    }

    public abstract String render();

    public abstract Observation getObservation();

    public abstract void replayToPosition(int stateIndex);

    public @NotNull List<Integer> getRandomActionsIndices(int i) {

        List<Integer> actionList = new ArrayList<>();
        for (int j = 0; j < i; j++) {
            actionList.add(r.nextInt(this.config.getActionSpaceSize()));
        }
        return actionList;
    }


    public abstract void renderNetworkGuess(MuZeroConfig config, Player toPlay, NetworkIO networkIO, boolean b);

    public abstract void renderSuggestionFromPriors(MuZeroConfig config, Node node);

    public abstract void renderMCTSSuggestion(MuZeroConfig config, float[] childVisits);

    public void beforeReplayWithoutChangingActionHistory() {
        this.originalGameDTO = this.gameDTO;
        this.gameDTO = this.gameDTO.copyWithoutActions();
        this.gameDTO.setPolicyTargets(this.originalGameDTO.getPolicyTargets());
        this.initEnvironment();
    }

    public void beforeReplayWithoutChangingActionHistory(int backInTime) {
        this.originalGameDTO = this.gameDTO;
        this.gameDTO = this.gameDTO.copy(this.gameDTO.getActions().size() - backInTime);
        this.gameDTO.setPolicyTargets(this.originalGameDTO.getPolicyTargets());
        this.initEnvironment();
        this.replayToPosition(getGameDTO().getActions().size());
    }


    public void afterReplay() {
        this.setOriginalGameDTO(null);
    }

    public abstract void initEnvironment();

    public void initSearchManager(double pRandomActionRawAverage) {
        searchManager = new GumbelSearch(config, this, debug, pRandomActionRawAverage);
    }

    double pRatioMax;
    public double getPRatioMax() {
        int n = getGameDTO().getActions().size();
        double[] pRatios = new double[n];
        IntStream.range(0, n).forEach(i -> {
            int a = getGameDTO().getActions().get(i);
           if (getGameDTO().getPlayoutPolicy().isEmpty()) {
               pRatios[i] = 1;
           } else {
               pRatios[i] = getGameDTO().getPolicyTargets().get(i)[a] / getGameDTO().getPlayoutPolicy().get(i)[a];
           }
        });
        double prod =  getProductPathMax(pRatios);
        if (prod > 100) {
            int j = 42;
        }
        return prod;
    }



//
//        //  GameBuffer gameBuffer, int currentIndex, int T)
//        int currentIndex =  gamePos;
//        int T = game.getGameDTO().getActions().size() - 1;
//        int tdSteps;
//        tdSteps = 0;
////            if (!config.offPolicyCorrectionOn()) return tdSteps;
//        if (game.getGameDTO().getPlayoutPolicy().isEmpty()) return 1;
//
//        double pRatioMax = 0;
//
//        double b = ThreadLocalRandom.current().nextDouble(0, 1);
//
//        for (int t = T; t >= currentIndex; t--) {
//
//            double pBase = 1;
//            for (int i = t; i <= T; i++) {
//                pBase *=  game.getGameDTO().getPlayoutPolicy().get(i)[game.getGameDTO().getActions().get(i)];
//            }
//            double p = 1;
//            for (int i = t; i <= T; i++) {
//                p *= game.getGameDTO().getPolicyTargets().get(i)[game.getGameDTO().getActions().get(i)];
//            }
//            double pRatio = p / pBase;
//            if (pRatio > pRatioMax) {
//                pRatioMax = pRatio;
//            }
//        }
//
//        return pRatioMax;
//    }
}
