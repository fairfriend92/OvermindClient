//
// Created by Rodolfo Rocco on 25/01/2019.
//

#ifndef OVERMINDCLIENT_SHARED_H
#define OVERMINDCLIENT_SHARED_H

#include <CL/cl.h>
#include <android/log.h>
#include <jni.h>
#include <math.h>
#include <sstream>

#define SAMPLING_RATE 0.5 // milliSeconds
#define SYNAPSE_FILTER_ORDER 16

struct OpenCLObject {
    // OpenCL implementation
    cl_context context = 0;
    cl_command_queue commandQueue = 0;
    cl_program program = 0;
    cl_device_id device = 0;
    cl_kernel kernel = 0;
    cl_int errorNumber = 0;
    int numberOfMemoryObjects = 11;
    cl_mem memoryObjects[11] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    cl_uint floatVectorWidth;
    size_t maxWorkGroupSize;

    // Pointers to the memory buffers
    cl_float *synapseCoeff;
    cl_float *synapseWeights;
    cl_uchar *synapseInput;
    cl_ushort *synapseIndexes;
    cl_int *current;
    cl_ushort *neuronsIndexes;
    cl_float *presynFiringRates;
    cl_float *postsynFiringRates;
    cl_float *updateWeightsFlags;
    cl_int *globalIdOffset;
    cl_int *weightsReservoir;
    double *neuronalDynVar;
};

void buildSynapticInput(int neuronsComputed, char actionPotentials[], int numOfNeurons,
                        int maxMultiplications, cl_uchar synapticInput[]);
void computeNeuronalDynamics(int neuronsComputed, int numOfNeurons, cl_int current[], jfloat simulationParameters[],
                             double neuronalDynVar[], cl_float postsynFiringRates[], char actionPotentials[], int weightsReservoir[]);
int printSynapticMaps(int counter, OpenCLObject *obj, size_t synapseWeightsBufferSize, int NUM_SYNAPSES);

#define LOGE(x...) do { \
  char buf[512]; \
  sprintf(buf, x); \
  __android_log_print(ANDROID_LOG_ERROR,"OpenCL error", "%s", buf); \
} while (0)

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "OpenCL debug", __VA_ARGS__)

#endif //OVERMINDCLIENT_SHARED_H
