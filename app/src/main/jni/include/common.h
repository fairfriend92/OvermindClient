//
// Created by rodolfo on 01/12/16.
//

#ifndef OVERMINDCLIENT_COMMON_H
#define OVERMINDCLIENT_COMMON_H

#include <shared.h>

bool printProfilingInfo(cl_event event);
bool cleanUpOpenCL(cl_context context, cl_command_queue commandQueue, cl_program program, cl_kernel kernel, cl_mem* memoryObjects, int numberOfMemoryObjects);
bool createContext(cl_context* context);
bool createCommandQueue(cl_context context, cl_command_queue* commandQueue, cl_device_id* device);
bool createProgram(cl_context context, cl_device_id device, const char* kernelString, cl_program* program);
std::string errorNumberToString(cl_int errorNumber);
bool checkSuccess(cl_int errorNumber);

#endif //OVERMINDCLIENT_COMMON_H
