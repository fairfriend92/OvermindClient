//
// Created by rodolfo on 27/11/16.
//

#include "native_method.h"

// Maximum number of multiplications needed to compute the current response of a synapse
 int maxNumberMultiplications = (int) (ABSOLUTE_REFRACTORY_PERIOD / SAMPLING_RATE );
// Size of the buffers needed to store data
size_t excSynapseInputBufferSize = maxNumberMultiplications * NUMBER_OF_EXC_SYNAPSES * sizeof(cl_ushort);
size_t excSynapseCoeffBufferSize = SYNAPSE_FILTER_ORDER * sizeof(cl_float);
//size_t excSynapseInputBufferSize = SYNAPSE_FILTER_ORDER * NUMBER_OF_EXC_SYNAPSES * sizeof(cl_int);
size_t synapseOutputBufferSize = NUMBER_OF_EXC_SYNAPSES * sizeof(cl_float);

extern "C" jlong Java_com_example_myapplication_Simulation_initializeOpenCL (
        JNIEnv *env, jobject thiz, jstring jMacKernel) {

    struct OpenCLObject *obj;
    obj = (struct OpenCLObject *)malloc(sizeof(struct OpenCLObject));

    const char *kernelString = env->GetStringUTFChars(jMacKernel, JNI_FALSE);

    if (!createContext(&obj->context))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to create an OpenCL context");
        return -1;
    }

    if (!createCommandQueue(obj->context, &obj->commandQueue, &obj->device))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to create an OpenCL command queue");
        return -1;
    }

    if (!createProgram(obj->context, obj->device, kernelString, &obj->program))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to create OpenCL program");
        return -1;
    }

    // TODO: use vector information to load appropriate kernel using case:switch
    // Query the device to find out its preferred integer vector width
    clGetDeviceInfo(obj->device, CL_DEVICE_PREFERRED_VECTOR_WIDTH_INT, sizeof(cl_uint), &obj->integerVectorWidth, NULL);
    LOGD("Preferred vector width for integers: %d ", obj->integerVectorWidth);

    obj->kernel = clCreateKernel(obj->program, "mac_kernel_vec4", &obj->errorNumber);
    if (!checkSuccess(obj->errorNumber))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to create OpenCL kernel");
        return -1;
    }

    // Release the string containing the kernel since it has been passed already to createProgram
    env->ReleaseStringUTFChars(jMacKernel, kernelString);

    bool createMemoryObjectsSuccess = true;
    obj->numberOfMemoryObjects= 3;

    // Ask the OpenCL implementation to allocate buffers to pass data to and from the kernels
    obj->memoryObjects[0] = clCreateBuffer(obj->context, CL_MEM_READ_ONLY| CL_MEM_ALLOC_HOST_PTR, excSynapseCoeffBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[1] = clCreateBuffer(obj->context, CL_MEM_READ_WRITE | CL_MEM_ALLOC_HOST_PTR, excSynapseInputBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[2] = clCreateBuffer(obj->context, CL_MEM_WRITE_ONLY | CL_MEM_ALLOC_HOST_PTR, synapseOutputBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    if (!createMemoryObjectsSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to create OpenCL buffer");
        return -1;
    }

    // Map the memory buffers created by the OpenCL implementation so we can access them on the CPU
    bool mapMemoryObjectsSuccess = true;

    obj->excSynapseCoeff = (cl_float*)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[0], CL_TRUE, CL_MAP_WRITE, 0, excSynapseCoeffBufferSize, 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->excSynapseInput = (cl_ushort *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[1], CL_TRUE, CL_MAP_WRITE, 0, excSynapseInputBufferSize, 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    if (!mapMemoryObjectsSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to map buffer");
        return -1;
    }

    // Initialize the coefficients array by sampling the exponential kernel of the synapse filter
    float tExc = (float) (SAMPLING_RATE / EXC_SYNAPSE_TIME_SCALE);
    // Extend the loop beyond the synapse filter order to account for possible overflows of the filter input
    for (int index = 0; index < SYNAPSE_FILTER_ORDER * 2; index++)
    {
        obj->excSynapseCoeff[index] = (index * tExc) * expf( - index * tExc);
    }

    /**
     * Initialize every element of the input array with -1. This allows to use simple multiplications
     * instead of if statements in the OpenCL kernel
     */
    for (int index = 0; index < maxNumberMultiplications * NUMBER_OF_EXC_SYNAPSES; index++)
    {
        obj->excSynapseInput[index] = 0;
    }

    return (long) obj;
}

extern "C" jlong Java_com_example_myapplication_Simulation_simulateNetwork(
        JNIEnv *env, jobject thiz, jbooleanArray jPresynapticSpikes, jlong jOpenCLObject) {

    jboolean *presynapticSpikes = env->GetBooleanArrayElements(jPresynapticSpikes, 0);

    struct OpenCLObject *obj;
    obj = (struct OpenCLObject *)jOpenCLObject;

    // TODO: move input initialization in a separate thread
    // Initialize the input of the kernel
    for (int indexI = 0; indexI < NUMBER_OF_EXC_SYNAPSES; indexI++)
    {
        /**
         * Advance the inputs in the filter pipeline if needed. We do not account for the case when an
         * input exceeds the synapse filter order. Instead we sample the exponential a number of time
         * greater than the synapse filter order.
         */
        if (presynapticSpikes[indexI])
        {
            for (int indexJ = 1; indexJ < maxNumberMultiplications; indexJ++)
            {
                obj->excSynapseInput[indexJ + indexI * maxNumberMultiplications] =
                obj->excSynapseInput[indexJ + indexI * maxNumberMultiplications - 1];

            }
            obj->excSynapseInput[indexI*maxNumberMultiplications] = 0;
        }
    }


    // Tell the kernels which data to use before they are scheduled
    bool setKernelArgumentSuccess = true;
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernel, 0, sizeof(cl_mem), &obj->memoryObjects[0]));
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernel, 1, sizeof(cl_mem), &obj->memoryObjects[1]));
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernel, 2, sizeof(cl_mem), &obj->memoryObjects[2]));

    if (!setKernelArgumentSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to set OpenCL kernel arguments");
        return -1;
    }

    //  An event to associate with the kernel. Allows us to to retrieve profiling information later
    cl_event event = 0;

    // Number of kernel instances
    size_t globalWorksize[1] = {NUMBER_OF_EXC_SYNAPSES};
    // Enqueue kernel
    if (!checkSuccess(clEnqueueNDRangeKernel(obj->commandQueue, obj->kernel, 1, NULL, globalWorksize, NULL, 0, NULL, &event)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to enqueue OpenCL kernel");
        return -1;
    }

    // Wait for kernel execution completion
    if (!checkSuccess(clFinish(obj->commandQueue)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed waiting for kernel execution to finish");
        return -1;
    }

    // Print the profiling information for the event
    if(!printProfilingInfo(event))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to print profiling info");
        return -1;
    }

    // Release the event object
    if (!checkSuccess(clReleaseEvent(event)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed releasing the event");
        return -1;
    }

    return (long) obj;
}

extern "C" int Java_com_example_myapplication_Simulation_closeOpenCL(
        JNIEnv *env, jobject thiz,  jlong jOpenCLObject) {

    struct OpenCLObject *obj;
    obj = (struct OpenCLObject *) jOpenCLObject;

    // Un-map the memory objects as we have finished using them from the CPU side
    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[0], obj->excSynapseCoeff, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
        return -1;
    }

    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[1], obj->excSynapseInput, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
        return -1;
    }

    if (!cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects))
    {
        LOGE("Failed to clean-up OpenCL");
        return -1;
    };

    free(obj);
    return 1;
}
