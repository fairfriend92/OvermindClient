/* This kernel is for devices with 128 bits wide registers. 
   It computes 4 synapses at a time. */

#pragma OPENCL EXTENSION cl_khr_fp64 : enable
#pragma OPENCL EXTENSION cl_khr_int64_base_atomics: enable

#define potential neuronalDynVar[workId * 3]
#define recovery neuronalDynVar[workId * 3 + 1]
#define kahanCompensation neuronalDynVar[workId * 3 + 2]

#define a simulationParameters[0]
#define b simulationParameters[1]
#define c simulationParameters[2]
#define d simulationParameters[3]
#define I simulationParameters[4]

__kernel 
void simulate_dynamics(__constant float* coeff, __constant uchar* weights,
		       __constant char* input, __global double* restrict current,
		       __global int* restrict counter, __global double* restrict neuronalDynVar,
		       __global uchar* restrict actionPotentials, __constant double* simulationParameters,
		       __local double* scratch)
{

  ushort localId = get_local_id(0);
  ushort workId = get_group_id(0);

  char16 index = vload16(localId, input);

  // Coefficients of the synapse kernel
  float4 coeffVec = (float4)(coeff[index.s0] + coeff[index.s1] + coeff[index.s2] + coeff[index.s3],
			     coeff[index.s4] + coeff[index.s5] + coeff[index.s6] + coeff[index.s7],
			     coeff[index.s8] + coeff[index.s9] + coeff[index.sa] + coeff[index.sb],
			     coeff[index.sc] + coeff[index.sd] + coeff[index.se] + coeff[index.sf]);
  // Synaptic weights
  float4 weightsVec = convert_float4(vload4(localId, weights + workId * 1024));

  scratch[localId] = (double)dot(coeffVec, weightsVec);

  barrier(CLK_LOCAL_MEM_FENCE);

  for (ushort offset = 1; offset < get_local_size(0); offset <<= 1)
    {
      ushort mask = (offset << 1) - 1;
      if ((localId & mask) == 0)
	{
	  scratch[localId] += scratch[localId + offset];
	}
    }

  // The counter is used to count the synapses that have been served
  atomic_inc(&counter[workId]);
 
  barrier(CLK_LOCAL_MEM_FENCE);

  if (localId == 0) { current[workId] = scratch[0] / 100.0f; }
  
  
}
    
  

