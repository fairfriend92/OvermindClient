//
// Created by Rodolfo Rocco on 28/09/2018.
//

#include "populations_methods.h"

/**
 * From the action potentials produced by the neurons it generates the indexes that the synapses
 * use to access the coefficients of the filter kernel. The algorithm is identical to that found in
 * KernelInitializer.
 *
 * @param neuronsComputed How many neurons have been served in the past iterations
 * @param actionPotentials: The array of bits from which the indexes are generated
 * @param numOfNeurons: How many neurons the input layer has (this is also the size of the
 *                      actionPotentials vector).
 * @param maxMultiplications: How many indexes are generated for each synapse.
 * @param synapticInput: The array in which the indexes should be stored.
 */

void buildSynapticInput(int neuronsComputed, char actionPotentials[], int numOfNeurons,
                        int maxMultiplications, cl_uchar synapticInput[]) {
    for (int i = neuronsComputed; i <neuronsComputed + numOfNeurons; i++) {
        short byteIndex = (short) (i / 8);
        char bitValue = (char) ((actionPotentials[byteIndex] >> (i - byteIndex * 8)) & 1);
        for (int j = (maxMultiplications - 1); j >= 1; j--) {
            synapticInput[j + i * maxMultiplications] =
                    (synapticInput[j + i * maxMultiplications - bitValue] != 0) && (synapticInput[j + i * maxMultiplications - bitValue] < maxMultiplications) ?
                    (cl_uchar) (synapticInput[j + i * maxMultiplications - bitValue] + 1) : (cl_uchar) 0;
        }

        switch (bitValue) {
            case 1:
                synapticInput[i * maxMultiplications] = 1;
                break;
            default:
                synapticInput[i * maxMultiplications] =
                        (synapticInput[i * maxMultiplications] != 0) && (synapticInput[i * maxMultiplications] < SYNAPSE_FILTER_ORDER) ?
                        (cl_uchar)(synapticInput[i * maxMultiplications] + 1) : (cl_uchar) 0;
                break;
        }

    }
}

/**
 * Simulate the neuronal dynamics for the neurons of a given layer and compute the action potentials
 * produced by said neurons. Additionally update the postsynaptic firing rates.
 *
 * @param neuronsComputed How many neurons have been served in the past iterations
 * @param numOfNeurons The number of neurons of the current layer
 * @param current The synaptic current
 * @param simulationParameters The parameters of the neuronal model
 * @param neuronalDynVar An array storing the variables that govern the dynamics, that is to say the
 *                        membrane potential and the recovery variable (for the Izhikevich neuron)
 * @param postsynFiringRates
 * @param actionPotentials
 */

void computeNeuronalDynamics(int neuronsComputed, int numOfNeurons, cl_int current[], jfloat simulationParameters[],
                             float neuronalDynVar[], cl_float postsynFiringRates[], char actionPotentials[]) {
    for (int i = neuronsComputed; i < neuronsComputed + numOfNeurons; i++) {
        // Convert back from int to float
        float currentFloat = (float)(current[i]) / 1000.0f;

        // Compute the potential and the recovery variable using Euler integration and Izhikevich model
        potentialVar += 0.04f * pow(potentialVar, 2) + 5.0f * potentialVar + 140.0f - recoveryVar + currentFloat + IPar;
        recoveryVar += aPar * (bPar * potentialVar - recoveryVar);

        //if (i == 0) LOGD("currentFloat %f current %d", currentFloat, current[i]);


        // Set the bits corresponding to the neurons that have fired and update the moving average of the firing rates
        if (potentialVar >= 30.0f)
        {
            actionPotentials[(short)(i / 8)] |= (1 << (i - (short)(i / 8) * 8));
            recoveryVar += dPar;
            potentialVar = cPar;
            postsynFiringRates[i] += (cl_float)(MEAN_RATE_INCREMENT * (1 - postsynFiringRates[i]));
        }
        else
        {
            actionPotentials[(short)(i / 8)] &= ~(1 << (i - (short)(i / 8) * 8));
            postsynFiringRates[i] -= (cl_float)(MEAN_RATE_INCREMENT * postsynFiringRates[i]);
        }

        //postsynFiringRates[i] += (cl_float)(MEAN_RATE_INCREMENT * (currentFloat - postsynFiringRates[i]));

        current[i] = (cl_int)0;
    }

}

/**
 * Function that prints on the console the list of synaptic weights for a given neuron. The function
 * does this only every given number of iterations.
 *
 * @param counter How many iterations have happened since the last time the function printed the list
 * @param obj The OpenCL object holding info about the memory buffers
 * @param synapseWeightsBufferSize The size of the memory buffer of the synaptic weights
 * @param NUM_SYNAPSES The number of synapses of this terminal
 * @return The counter increased by one or reset if the function printed the list
 */

int printSynapticMaps(int counter, OpenCLObject *obj, size_t synapseWeightsBufferSize, int NUM_SYNAPSES) {
    bool mapMemoryObjectsSuccess = true;

    if (counter == 1000) {
        obj->synapseWeights = (cl_float*)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[1], CL_TRUE, CL_MAP_READ, 0, synapseWeightsBufferSize, 0, NULL, NULL, &obj->errorNumber);
        mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

        if (!mapMemoryObjectsSuccess)
        {
            cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
            LOGE("Failed to map buffer");
        }

        /*
        for (int weightIndex = 576; weightIndex < 640; weightIndex++) {
            LOGE("%lf", obj->synapseWeights[weightIndex]);
        }
        */


        if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[1], obj->synapseWeights, 0, NULL, NULL)))
        {
            cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
            LOGE("Unmap memory objects failed");
        }

        obj->updateWeightsFlags = (cl_float*)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[7], CL_TRUE, CL_MAP_READ, 0, synapseWeightsBufferSize, 0, NULL, NULL, &obj->errorNumber);
        mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

        if (!mapMemoryObjectsSuccess)
        {
            cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
            LOGE("Failed to map buffer");
        }

        for (int i = 640; i < 1424; i++) {
            LOGE("%f ", obj->updateWeightsFlags[i]);
        }

        if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[7], obj->updateWeightsFlags, 0, NULL, NULL)))
        {
            cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
            LOGE("Unmap memory objects failed");
        }

    }

    // Decrease counter and reset it when it becomes zero
    counter = counter == 0 ? 1000 : counter - 1;

    return counter;
}