//
// Created by rodolfo on 27/11/16.
//

#include <android/log.h>
#include <jni.h>
#include <math.h>
#include "CL/cl.h"
#include "common.h"

#define SAMPLING_RATE 0.1
#define SYNAPSE_FILTER_ORDER 64
#define EXC_SYNAPSE_TIME_SCALE 1
#define NUMBER_OF_EXC_SYNAPSES 4

extern "C"
jboolean
Java_com_example_myapplication_Simulation_helloWorld(
        JNIEnv *env, jobject thiz, jbooleanArray presynapticSpikes, jstring jMacKernel) {

    bool result = true;
    jboolean jresult = (jboolean) result;
    __android_log_print(ANDROID_LOG_INFO, "hello_world_opencl", "prova");

    cl_context context = 0;
    cl_command_queue commandQueue = 0;
    cl_program program = 0;
    cl_device_id device = 0;
    cl_kernel kernel = 0;
    cl_int errorNumber = 0;
    int numberOfMemoryObjects = 3;
    cl_mem memoryObjects[3] = {0, 0, 0};

    const char *kernelString = env->GetStringUTFChars(jMacKernel, JNI_FALSE);

    /* [Initialize OpenCL] */
    if (!createContext(&context))
    {
        cleanUpOpenCL(context, commandQueue, program, kernel, memoryObjects, numberOfMemoryObjects);
        LOGE("Failed to create an OpenCL context");
        return 1;
    }

    if (!createCommandQueue(context, &commandQueue, &device))
    {
        cleanUpOpenCL(context, commandQueue, program, kernel, memoryObjects, numberOfMemoryObjects);
        LOGE("Failed to create an OpenCL command queue");
        return 1;
    }

    /*
    if (!createProgram(context, device, kernelString, &program))
    {
        cleanUpOpenCL(context, commandQueue, program, kernel, memoryObjects, numberOfMemoryObjects);
        LOGE("Failed to create OpenCL program");
        return 1;
    }
    /* [Initialize OpenCL] */

    /* [Query preferred vector width] */
    // Query the device to find out its preferred integer vector width
    cl_uint integerVectorWidth;
    clGetDeviceInfo(device, CL_DEVICE_PREFERRED_VECTOR_WIDTH_INT, sizeof(cl_uint), &integerVectorWidth, NULL);
    LOGD("Preferred vector width for integers: %d ", integerVectorWidth);
    /* [Query preferred vector width] */

    /* [Setup memory] */
    size_t excSynapseCoeffBufferSize = SYNAPSE_FILTER_ORDER * sizeof(cl_float);
    size_t excSynapseInputBufferSize = SYNAPSE_FILTER_ORDER * NUMBER_OF_EXC_SYNAPSES * sizeof(cl_bool);
    size_t synapseOutputBufferSize = sizeof(cl_uint);
    // Ask the OpenCL implementation to allocate buffers to pass data to and from the kernels
    bool createMemoryObjectsSuccess = true;

    memoryObjects[0] = clCreateBuffer(context, CL_MEM_READ_ONLY | CL_MEM_ALLOC_HOST_PTR, excSynapseCoeffBufferSize, NULL, &errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(errorNumber);

    memoryObjects[1] = clCreateBuffer(context, CL_MEM_READ_ONLY | CL_MEM_ALLOC_HOST_PTR, excSynapseInputBufferSize, NULL, &errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(errorNumber);

    memoryObjects[2] = clCreateBuffer(context, CL_MEM_WRITE_ONLY | CL_MEM_ALLOC_HOST_PTR, synapseOutputBufferSize, NULL, &errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(errorNumber);

    if (!createMemoryObjectsSuccess)
    {
        cleanUpOpenCL(context, commandQueue, program, kernel, memoryObjects, numberOfMemoryObjects);
        LOGE("Failed to create OpenCL buffer");
        return 1;
    }
    /* [Setup memory] */

    jboolean *jPresynapticSpikeTrain = env->GetBooleanArrayElements(presynapticSpikes, 0);

    /* [Map the buffers to pointers] */
    // Map the memory buffers created by the OpenCL implementation so we can access them on the CPU
    bool mapMemoryObjectsSuccess = true;

    cl_float* excSynapseCoeff = (cl_float*)clEnqueueMapBuffer(commandQueue, memoryObjects[0], CL_TRUE, CL_MAP_WRITE, 0, excSynapseCoeffBufferSize, 0, NULL, NULL, &errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(errorNumber);

    cl_bool* excSynapseInput = (cl_bool*)clEnqueueMapBuffer(commandQueue, memoryObjects[1], CL_TRUE, CL_MAP_WRITE, 0, excSynapseInputBufferSize, 0, NULL, NULL, &errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(errorNumber);

    if (!mapMemoryObjectsSuccess)
    {
        cleanUpOpenCL(context, commandQueue, program, kernel, memoryObjects, numberOfMemoryObjects);
        LOGE("Failed to map buffer");
        return 1;
    }
    /* [Map the buffers to pointers] */

    /* TODO: here we must start the while loop */

    /* [Initialize the data] */
    int index;
    // Do the memory management for the synapse filter
    for (index = 0; index < (SYNAPSE_FILTER_ORDER - 1) * NUMBER_OF_EXC_SYNAPSES; index++)
    {
        excSynapseInput[index + SYNAPSE_FILTER_ORDER] = excSynapseInput[index];
    }
    for (index = 0; index < NUMBER_OF_EXC_SYNAPSES; index++)
    {
        excSynapseInput[index] = jPresynapticSpikeTrain[index];
        if (jPresynapticSpikeTrain[index])
            LOGD("%d true", index);
    }
    // TODO: initialize the coefficients array outside of the while loop?
    // Initialize the coefficients array by sampling the exponential kernel of the synapse filter
    float tExc = SAMPLING_RATE / EXC_SYNAPSE_TIME_SCALE;
    for (index = 0; index < SYNAPSE_FILTER_ORDER; index++)
    {
        excSynapseCoeff[index] = (index * tExc) * expf( - index * tExc);
    }
    /* [Initialize the data] */

    /* TODO: here ends the while loop */

    /* [Un-map the buffers] */
    // Un-map the memory objects as we have finished using them from the CPU side
    if (!checkSuccess(clEnqueueUnmapMemObject(commandQueue, memoryObjects[0], excSynapseCoeff, 0, NULL, NULL)))
    {
        cleanUpOpenCL(context, commandQueue, program, kernel, memoryObjects, numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
        return 1;
    }

    if (!checkSuccess(clEnqueueUnmapMemObject(commandQueue, memoryObjects[1], excSynapseInput, 0, NULL, NULL)))
    {
        cleanUpOpenCL(context, commandQueue, program, kernel, memoryObjects, numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
        return 1;
    }
    /* [Un-map the buffers] */

    /* [Clean-up operations] */
    env->ReleaseStringUTFChars(jMacKernel, kernelString);
    cleanUpOpenCL(context, commandQueue, program, kernel, memoryObjects, numberOfMemoryObjects);
    /* [Clean-up operations] */

    return jresult;
}



