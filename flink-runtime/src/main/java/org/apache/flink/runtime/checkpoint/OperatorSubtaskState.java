/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.checkpoint;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.runtime.state.ChannelState;
import org.apache.flink.runtime.state.CompositeStateHandle;
import org.apache.flink.runtime.state.InputStateHandle;
import org.apache.flink.runtime.state.KeyedStateHandle;
import org.apache.flink.runtime.state.OperatorStateHandle;
import org.apache.flink.runtime.state.OutputStateHandle;
import org.apache.flink.runtime.state.SharedStateRegistry;
import org.apache.flink.runtime.state.SharedStateRegistryImpl.EmptyDiscardStateObjectForRegister;
import org.apache.flink.runtime.state.SharedStateRegistryKey;
import org.apache.flink.runtime.state.StateObject;
import org.apache.flink.runtime.state.StateUtil;
import org.apache.flink.runtime.state.filemerging.FileMergingOperatorStreamStateHandle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.flink.runtime.state.ChannelStateHelper.collectUniqueDisposableInChannelState;
import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * This class encapsulates the state for one parallel instance of an operator. The complete state of
 * a (logical) operator (e.g. a flatmap operator) consists of the union of all {@link
 * OperatorSubtaskState}s from all parallel tasks that physically execute parallelized, physical
 * instances of the operator.
 *
 * <p>The full state of the logical operator is represented by {@link OperatorState} which consists
 * of {@link OperatorSubtaskState}s.
 *
 * <p>Typically, we expect all collections in this class to be of size 0 or 1, because there is up
 * to one state handle produced per state type (e.g. managed-keyed, raw-operator, ...). In
 * particular, this holds when taking a snapshot. The purpose of having the state handles in
 * collections is that this class is also reused in restoring state. Under normal circumstances, the
 * expected size of each collection is still 0 or 1, except for scale-down. In scale-down, one
 * operator subtask can become responsible for the state of multiple previous subtasks. The
 * collections can then store all the state handles that are relevant to build up the new subtask
 * state.
 */
public class OperatorSubtaskState implements CompositeStateHandle {

    private static final Logger LOG = LoggerFactory.getLogger(OperatorSubtaskState.class);

    private static final long serialVersionUID = -2394696997971923995L;

    /** Snapshot from the {@link org.apache.flink.runtime.state.OperatorStateBackend}. */
    private final StateObjectCollection<OperatorStateHandle> managedOperatorState;

    /**
     * Snapshot written using {@link
     * org.apache.flink.runtime.state.OperatorStateCheckpointOutputStream}.
     */
    private final StateObjectCollection<OperatorStateHandle> rawOperatorState;

    /** Snapshot from {@link org.apache.flink.runtime.state.KeyedStateBackend}. */
    private final StateObjectCollection<KeyedStateHandle> managedKeyedState;

    /**
     * Snapshot written using {@link
     * org.apache.flink.runtime.state.KeyedStateCheckpointOutputStream}.
     */
    private final StateObjectCollection<KeyedStateHandle> rawKeyedState;

    private final StateObjectCollection<InputStateHandle> inputChannelState;

    private final StateObjectCollection<OutputStateHandle> resultSubpartitionState;

    /**
     * The subpartitions mappings per partition set when the output operator for a partition was
     * rescaled. The key is the partition id and the value contains all subtask indexes of the
     * output operator before rescaling. Note that this field is only set by {@link
     * StateAssignmentOperation} and will not be persisted in the checkpoint itself as it can only
     * be calculated if the post-recovery scale factor is known.
     */
    private final InflightDataRescalingDescriptor inputRescalingDescriptor;

    /**
     * The input channel mappings per input set when the input operator for a gate was rescaled. The
     * key is the gate index and the value contains all subtask indexes of the input operator before
     * rescaling. Note that this field is only set by {@link StateAssignmentOperation} and will not
     * be persisted in the checkpoint itself as it can only be calculated if the post-recovery scale
     * factor is known.
     */
    private final InflightDataRescalingDescriptor outputRescalingDescriptor;

    /**
     * The state size. This is also part of the deserialized state handle. We store it here in order
     * to not deserialize the state handle when gathering stats.
     */
    private final long stateSize;

    private final long checkpointedSize;

    private OperatorSubtaskState(
            StateObjectCollection<OperatorStateHandle> managedOperatorState,
            StateObjectCollection<OperatorStateHandle> rawOperatorState,
            StateObjectCollection<KeyedStateHandle> managedKeyedState,
            StateObjectCollection<KeyedStateHandle> rawKeyedState,
            StateObjectCollection<InputStateHandle> inputChannelState,
            StateObjectCollection<OutputStateHandle> resultSubpartitionState,
            InflightDataRescalingDescriptor inputRescalingDescriptor,
            InflightDataRescalingDescriptor outputRescalingDescriptor) {

        this.managedOperatorState = checkNotNull(managedOperatorState);
        this.rawOperatorState = checkNotNull(rawOperatorState);
        this.managedKeyedState = checkNotNull(managedKeyedState);
        this.rawKeyedState = checkNotNull(rawKeyedState);
        this.inputChannelState = checkNotNull(inputChannelState);
        this.resultSubpartitionState = checkNotNull(resultSubpartitionState);
        this.inputRescalingDescriptor = checkNotNull(inputRescalingDescriptor);
        this.outputRescalingDescriptor = checkNotNull(outputRescalingDescriptor);

        this.stateSize = streamSubCollections().mapToLong(StateObject::getStateSize).sum();
        this.checkpointedSize =
                streamSubCollections().mapToLong(StateObjectCollection::getCheckpointedSize).sum();
    }

    private Stream<StateObjectCollection<?>> streamSubCollections() {
        return Stream.of(streamOperatorAndKeyedStates(), streamChannelStates())
                .flatMap(Function.identity());
    }

    private Stream<StateObjectCollection<? extends StateObject>> streamOperatorAndKeyedStates() {
        return Stream.of(managedOperatorState, rawOperatorState, managedKeyedState, rawKeyedState)
                .filter(Objects::nonNull);
    }

    private Stream<StateObjectCollection<? extends ChannelState>> streamChannelStates() {
        return Stream.of(inputChannelState, resultSubpartitionState).filter(Objects::nonNull);
    }

    @VisibleForTesting
    OperatorSubtaskState() {
        this(
                StateObjectCollection.empty(),
                StateObjectCollection.empty(),
                StateObjectCollection.empty(),
                StateObjectCollection.empty(),
                StateObjectCollection.empty(),
                StateObjectCollection.empty(),
                InflightDataRescalingDescriptor.NO_RESCALE,
                InflightDataRescalingDescriptor.NO_RESCALE);
    }

    // --------------------------------------------------------------------------------------------

    public StateObjectCollection<OperatorStateHandle> getManagedOperatorState() {
        return managedOperatorState;
    }

    public StateObjectCollection<OperatorStateHandle> getRawOperatorState() {
        return rawOperatorState;
    }

    public StateObjectCollection<KeyedStateHandle> getManagedKeyedState() {
        return managedKeyedState;
    }

    public StateObjectCollection<KeyedStateHandle> getRawKeyedState() {
        return rawKeyedState;
    }

    public StateObjectCollection<InputStateHandle> getInputChannelState() {
        return inputChannelState;
    }

    public StateObjectCollection<OutputStateHandle> getResultSubpartitionState() {
        return resultSubpartitionState;
    }

    public InflightDataRescalingDescriptor getInputRescalingDescriptor() {
        return inputRescalingDescriptor;
    }

    public InflightDataRescalingDescriptor getOutputRescalingDescriptor() {
        return outputRescalingDescriptor;
    }

    public List<StateObject> getDiscardables() {
        return Stream.concat(
                        streamOperatorAndKeyedStates().flatMap(Collection::stream),
                        collectUniqueDisposableInChannelState(streamChannelStates()))
                .collect(Collectors.toList());
    }

    @Override
    public void discardState() {
        try {
            List<StateObject> toDispose = getDiscardables();
            StateUtil.bestEffortDiscardAllStateObjects(toDispose);
        } catch (Exception e) {
            LOG.warn("Error while discarding operator states.", e);
        }
    }

    @Override
    public void registerSharedStates(SharedStateRegistry sharedStateRegistry, long checkpointID) {
        registerSharedState(sharedStateRegistry, managedKeyedState, checkpointID);
        registerSharedState(sharedStateRegistry, rawKeyedState, checkpointID);
        registerFileMergingDirectoryHandle(
                sharedStateRegistry,
                managedOperatorState.stream()
                        .filter(e -> e instanceof FileMergingOperatorStreamStateHandle)
                        .map(e -> (FileMergingOperatorStreamStateHandle) e)
                        .collect(Collectors.toList()),
                checkpointID);
    }

    private static void registerSharedState(
            SharedStateRegistry sharedStateRegistry,
            Iterable<KeyedStateHandle> stateHandles,
            long checkpointID) {
        for (KeyedStateHandle stateHandle : stateHandles) {
            if (stateHandle != null) {
                // Registering state handle to the given sharedStateRegistry serves one purpose:
                // update the status of the checkpoint in sharedStateRegistry to which the state
                // handle belongs.
                sharedStateRegistry.registerReference(
                        new SharedStateRegistryKey(stateHandle.getStateHandleId().getKeyString()),
                        new EmptyDiscardStateObjectForRegister(stateHandle.getStateHandleId()),
                        checkpointID);
                stateHandle.registerSharedStates(sharedStateRegistry, checkpointID);
            }
        }
    }

    private static void registerFileMergingDirectoryHandle(
            SharedStateRegistry sharedStateRegistry,
            Iterable<FileMergingOperatorStreamStateHandle> stateHandles,
            long checkpointID) {
        for (FileMergingOperatorStreamStateHandle stateHandle : stateHandles) {
            if (stateHandle != null) {
                stateHandle.registerSharedStates(sharedStateRegistry, checkpointID);
            }
        }
    }

    @Override
    public long getCheckpointedSize() {
        return checkpointedSize;
    }

    @Override
    public long getStateSize() {
        return stateSize;
    }

    public boolean isFinished() {
        return false;
    }

    // --------------------------------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        OperatorSubtaskState that = (OperatorSubtaskState) o;

        if (getStateSize() != that.getStateSize()) {
            return false;
        }
        if (!getManagedOperatorState().equals(that.getManagedOperatorState())) {
            return false;
        }
        if (!getRawOperatorState().equals(that.getRawOperatorState())) {
            return false;
        }
        if (!getManagedKeyedState().equals(that.getManagedKeyedState())) {
            return false;
        }
        if (!getInputChannelState().equals(that.getInputChannelState())) {
            return false;
        }
        if (!getResultSubpartitionState().equals(that.getResultSubpartitionState())) {
            return false;
        }
        if (!getInputRescalingDescriptor().equals(that.getInputRescalingDescriptor())) {
            return false;
        }
        if (!getOutputRescalingDescriptor().equals(that.getOutputRescalingDescriptor())) {
            return false;
        }
        return getRawKeyedState().equals(that.getRawKeyedState());
    }

    @Override
    public int hashCode() {
        int result = getManagedOperatorState().hashCode();
        result = 31 * result + getRawOperatorState().hashCode();
        result = 31 * result + getManagedKeyedState().hashCode();
        result = 31 * result + getRawKeyedState().hashCode();
        result = 31 * result + getInputChannelState().hashCode();
        result = 31 * result + getResultSubpartitionState().hashCode();
        result = 31 * result + getInputRescalingDescriptor().hashCode();
        result = 31 * result + getOutputRescalingDescriptor().hashCode();
        result = 31 * result + (int) (getStateSize() ^ (getStateSize() >>> 32));
        result = 31 * result + (int) (getCheckpointedSize() ^ (getCheckpointedSize() >>> 32));
        return result;
    }

    @Override
    public String toString() {
        return "SubtaskState{"
                + "operatorStateFromBackend="
                + managedOperatorState
                + ", operatorStateFromStream="
                + rawOperatorState
                + ", keyedStateFromBackend="
                + managedKeyedState
                + ", keyedStateFromStream="
                + rawKeyedState
                + ", inputChannelState="
                + inputChannelState
                + ", resultSubpartitionState="
                + resultSubpartitionState
                + ", stateSize="
                + stateSize
                + ", checkpointedSize="
                + checkpointedSize
                + '}';
    }

    public boolean hasState() {
        return managedOperatorState.hasState()
                || rawOperatorState.hasState()
                || managedKeyedState.hasState()
                || rawKeyedState.hasState()
                || inputChannelState.hasState()
                || resultSubpartitionState.hasState();
    }

    public Builder toBuilder() {
        return builder()
                .setManagedKeyedState(managedKeyedState)
                .setManagedOperatorState(managedOperatorState)
                .setRawOperatorState(rawOperatorState)
                .setRawKeyedState(rawKeyedState)
                .setInputChannelState(inputChannelState)
                .setResultSubpartitionState(resultSubpartitionState)
                .setInputRescalingDescriptor(inputRescalingDescriptor)
                .setOutputRescalingDescriptor(outputRescalingDescriptor);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The builder for a new {@link OperatorSubtaskState} which can be obtained by {@link
     * #builder()}.
     */
    public static class Builder {
        private StateObjectCollection<OperatorStateHandle> managedOperatorState =
                StateObjectCollection.empty();
        private StateObjectCollection<OperatorStateHandle> rawOperatorState =
                StateObjectCollection.empty();
        private StateObjectCollection<KeyedStateHandle> managedKeyedState =
                StateObjectCollection.empty();
        private StateObjectCollection<KeyedStateHandle> rawKeyedState =
                StateObjectCollection.empty();
        private StateObjectCollection<InputStateHandle> inputChannelState =
                StateObjectCollection.empty();
        private StateObjectCollection<OutputStateHandle> resultSubpartitionState =
                StateObjectCollection.empty();
        private InflightDataRescalingDescriptor inputRescalingDescriptor =
                InflightDataRescalingDescriptor.NO_RESCALE;
        private InflightDataRescalingDescriptor outputRescalingDescriptor =
                InflightDataRescalingDescriptor.NO_RESCALE;

        private Builder() {}

        public Builder setManagedOperatorState(
                StateObjectCollection<OperatorStateHandle> managedOperatorState) {
            this.managedOperatorState = checkNotNull(managedOperatorState);
            return this;
        }

        public Builder setManagedOperatorState(OperatorStateHandle managedOperatorState) {
            return setManagedOperatorState(
                    StateObjectCollection.singleton(checkNotNull(managedOperatorState)));
        }

        public Builder setRawOperatorState(
                StateObjectCollection<OperatorStateHandle> rawOperatorState) {
            this.rawOperatorState = checkNotNull(rawOperatorState);
            return this;
        }

        public Builder setRawOperatorState(OperatorStateHandle rawOperatorState) {
            return setRawOperatorState(
                    StateObjectCollection.singleton(checkNotNull(rawOperatorState)));
        }

        public Builder setManagedKeyedState(
                StateObjectCollection<KeyedStateHandle> managedKeyedState) {
            this.managedKeyedState = checkNotNull(managedKeyedState);
            return this;
        }

        public Builder setManagedKeyedState(KeyedStateHandle managedKeyedState) {
            return setManagedKeyedState(
                    StateObjectCollection.singleton(checkNotNull(managedKeyedState)));
        }

        public Builder setRawKeyedState(StateObjectCollection<KeyedStateHandle> rawKeyedState) {
            this.rawKeyedState = checkNotNull(rawKeyedState);
            return this;
        }

        public Builder setRawKeyedState(KeyedStateHandle rawKeyedState) {
            return setRawKeyedState(StateObjectCollection.singleton(checkNotNull(rawKeyedState)));
        }

        public Builder setInputChannelState(
                StateObjectCollection<InputStateHandle> inputChannelState) {
            this.inputChannelState = checkNotNull(inputChannelState);
            return this;
        }

        public Builder setResultSubpartitionState(
                StateObjectCollection<OutputStateHandle> resultSubpartitionState) {
            this.resultSubpartitionState = checkNotNull(resultSubpartitionState);
            return this;
        }

        public Builder setInputRescalingDescriptor(
                InflightDataRescalingDescriptor inputRescalingDescriptor) {
            this.inputRescalingDescriptor = checkNotNull(inputRescalingDescriptor);
            return this;
        }

        public Builder setOutputRescalingDescriptor(
                InflightDataRescalingDescriptor outputRescalingDescriptor) {
            this.outputRescalingDescriptor = checkNotNull(outputRescalingDescriptor);
            return this;
        }

        public OperatorSubtaskState build() {
            return new OperatorSubtaskState(
                    managedOperatorState,
                    rawOperatorState,
                    managedKeyedState,
                    rawKeyedState,
                    inputChannelState,
                    resultSubpartitionState,
                    inputRescalingDescriptor,
                    outputRescalingDescriptor);
        }
    }
}
