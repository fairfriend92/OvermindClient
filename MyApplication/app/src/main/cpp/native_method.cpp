//
// Created by rodolfo on 27/11/16.
//

#include "native_method.h"

// Maximum number of multiplications needed to compute the current response of a synapse
 int maxNumberMultiplications = (int) (SYNAPSE_FILTER_ORDER * SAMPLING_RATE / ABSOLUTE_REFRACTORY_PERIOD + 1);
// Size of the buffers needed to store data
size_t synapseInputBufferSize = maxNumberMultiplications * (NUMBER_OF_EXC_SYNAPSES + NUMBER_OF_INH_SYNAPSES) * sizeof(cl_uchar);
size_t excSynapseCoeffBufferSize = SYNAPSE_FILTER_ORDER * sizeof(cl_uint);
size_t inhSynapseCoeffBufferSize = SYNAPSE_FILTER_ORDER * sizeof(cl_uint);
size_t synapseOutputBufferSize = (NUMBER_OF_EXC_SYNAPSES + NUMBER_OF_INH_SYNAPSES) * sizeof(cl_int);

extern "C" jlong Java_com_example_overmind_SimulationService_initializeOpenCL (
        JNIEnv *env, jobject thiz, jstring jSynapseKernel) {

    struct OpenCLObject *obj;
    obj = (struct OpenCLObject *)malloc(sizeof(struct OpenCLObject));

    const char *kernelString = env->GetStringUTFChars(jSynapseKernel, JNI_FALSE);

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

    // Query the device to find out its preferred integer vector width
    clGetDeviceInfo(obj->device, CL_DEVICE_PREFERRED_VECTOR_WIDTH_INT, sizeof(cl_uint), &obj->intVectorWidth, NULL);
    LOGD("Preferred vector width for integers: %d ", obj->intVectorWidth);

    switch (obj->intVectorWidth)
    {
        case 4:
            obj->kernel = clCreateKernel(obj->program, "synapse_vec4", &obj->errorNumber);
            if (!checkSuccess(obj->errorNumber))
            {
                cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
                LOGE("Failed to create OpenCL kernel");
                return -1;
            }
            break;
        default :
            LOGE("Failed to load kernel for device vector width");
    }

    // Release the string containing the kernel since it has been passed already to createProgram
    env->ReleaseStringUTFChars(jSynapseKernel, kernelString);

    bool createMemoryObjectsSuccess = true;
    obj->numberOfMemoryObjects= 3;

    // Ask the OpenCL implementation to allocate buffers to pass data to and from the kernels
    obj->memoryObjects[0] = clCreateBuffer(obj->context, CL_MEM_READ_ONLY| CL_MEM_ALLOC_HOST_PTR, excSynapseCoeffBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[1] = clCreateBuffer(obj->context, CL_MEM_READ_ONLY| CL_MEM_ALLOC_HOST_PTR, inhSynapseCoeffBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[2] = clCreateBuffer(obj->context, CL_MEM_READ_ONLY | CL_MEM_ALLOC_HOST_PTR, synapseInputBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[3] = clCreateBuffer(obj->context, CL_MEM_WRITE_ONLY | CL_MEM_ALLOC_HOST_PTR, synapseOutputBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    if (!createMemoryObjectsSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to create OpenCL buffer");
        return -1;
    }

    // Map the memory buffers created by the OpenCL implementation so we can access them on the CPU
    bool mapMemoryObjectsSuccess = true;

    obj->excSynapseCoeff = (cl_uint*)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[0], CL_TRUE, CL_MAP_WRITE, 0, excSynapseCoeffBufferSize, 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->inhSynapseCoeff = (cl_uint*)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[1], CL_TRUE, CL_MAP_WRITE, 0, inhSynapseCoeffBufferSize, 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->synapseInput = (cl_uchar *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[2], CL_TRUE, CL_MAP_WRITE, 0, synapseInputBufferSize, 0, NULL, NULL, &obj->errorNumber);
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
        obj->excSynapseCoeff[index] = (cl_uint)(index * tExc * expf( - index * tExc) * pow(2, SHIFT_FACTOR));
        //LOGD("The excitatory synapse coefficients are: \tcoefficient %d \tvalue %f", index, (float) (obj->excSynapseCoeff[index] / pow(2, SHIFT_FACTOR)));
    }

    float tInh = (float) (SAMPLING_RATE / INH_SYNAPSE_TIME_SCALE);
    // Extend the loop beyond the synapse filter order to account for possible overflows of the filter input
    for (int index = 0; index < SYNAPSE_FILTER_ORDER * 2; index++)
    {
        obj->inhSynapseCoeff[index] = (cl_uint)(index * tInh * expf( - index * tInh) * pow(2, SHIFT_FACTOR));
        //LOGD("The excitatory synapse coefficients are: \tcoefficient %d \tvalue %f", index, (float) (obj->excSynapseCoeff[index] / pow(2, SHIFT_FACTOR)));
    }

    /**
     * Initialize every element of the input array with -1. This allows to use simple multiplications
     * instead of if statements in the OpenCL kernel
     */
    for (int index = 0; index < maxNumberMultiplications * NUMBER_OF_EXC_SYNAPSES; index++)
    {
        obj->synapseInput[index] = 0;
    }

    return (long) obj;
}

extern "C" jlong Java_com_example_overmind_SimulationService_simulateDynamics(
        JNIEnv *env, jobject thiz, jcharArray jSynapseInput, jlong jOpenCLObject) {

    struct OpenCLObject *obj;
    obj = (struct OpenCLObject *)jOpenCLObject;

    jchar *synapseInput = env->GetCharArrayElements(jSynapseInput, JNI_FALSE);

    for (int index = 0; index < maxNumberMultiplications * (NUMBER_OF_EXC_SYNAPSES + NUMBER_OF_INH_SYNAPSES); index++)
    {
        obj->synapseInput[index] = (cl_uchar)synapseInput[index];
    }

    env->ReleaseCharArrayElements(jSynapseInput, synapseInput, 0);

    // Tell the kernels which data to use before they are scheduled
    bool setKernelArgumentSuccess = true;
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernel, 0, sizeof(cl_mem), &obj->memoryObjects[0]));
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernel, 1, sizeof(cl_mem), &obj->memoryObjects[1]));
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernel, 2, sizeof(cl_mem), &obj->memoryObjects[2]));
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernel, 3, sizeof(cl_mem), &obj->memoryObjects[3]));

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

extern "C" int Java_com_example_overmind_SimulationService_closeOpenCL(
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

    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[1], obj->inhSynapseCoeff, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
        return -1;
    }

    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[2], obj->synapseInput, 0, NULL, NULL)))
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