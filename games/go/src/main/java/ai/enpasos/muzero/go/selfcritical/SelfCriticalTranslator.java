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

package ai.enpasos.muzero.go.selfcritical;

import ai.djl.engine.Engine;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDArrays;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import ai.enpasos.muzero.platform.agent.fast.model.NetworkIO;
import ai.enpasos.muzero.platform.agent.fast.model.Observation;
import ai.enpasos.muzero.platform.agent.gamebuffer.Game;
import ai.enpasos.muzero.platform.environment.OneOfTwoPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;


public class SelfCriticalTranslator implements Translator<SelfCriticalDataSet, List<Integer>> {


    @Override
    public @Nullable Batchifier getBatchifier() {
        return null;
    }

    @Override
    public List<Integer> processOutput(TranslatorContext ctx, @NotNull NDList list) {

        List<Integer> result = new ArrayList<>();
        long length = list.get(0).getShape().get(0);
        NDArray softmaxed = list.get(0).softmax(1);
        for (int i = 0; i < length; i++) {
            float[] probabilities = softmaxed.get(i).toFloatArray();
            float max = 0;
            int maxI = -1;
            for (int k = 0; k < probabilities.length; k++) {
                if (probabilities[k] > max) {
                    max = probabilities[k];
                    maxI = k;
                }
            }

            result.add(maxI);
        }
        return result;
    }

    @Override
    public @NotNull NDList processInput(@NotNull TranslatorContext ctx, @NotNull SelfCriticalDataSet dataSet) {
        List<SelfCriticalGame> gameList = dataSet.data;
        int maxFullMoves = dataSet.maxFullMoves;

        int length = gameList.size();

        float[] dataArray = new float[length * 2 * (maxFullMoves+1)];



        for (int i = 0; i < length; i++) {
            SelfCriticalGame game = gameList.get(i);
            //int move = 0;
            fillDataForOneGame(maxFullMoves, dataArray, i, game);
        }



        NDArray[]  data = new NDArray[]{ctx.getNDManager().create(dataArray, new Shape(length, 1, 2, dataSet.maxFullMoves + 1))};


        return new NDList(data);
    }

    static void fillDataForOneGame(int maxFullMoves, float[] dataArray, int i, SelfCriticalGame game) {
        for (int p = 0; p < 2; p++) {
            OneOfTwoPlayer player = (p == 0) ? OneOfTwoPlayer.PLAYER_A : OneOfTwoPlayer.PLAYER_B;
            for (int fullMove = 0; fullMove < maxFullMoves; fullMove++) {
                SelfCriticalPosition pos = SelfCriticalPosition.builder().player(player).fullMove(fullMove).build();
                if (game.normalizedEntropyValues.containsKey(pos)) {
                    float entropy = game.normalizedEntropyValues.get(pos);
                    dataArray[i * 2 * (maxFullMoves + 1) + p * (maxFullMoves + 1) + fullMove] = entropy;
                }
            }
        }
    }


}
