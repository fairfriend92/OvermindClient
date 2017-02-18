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

__kernel void simulate_dynamics(__constant float* coeff, __constant uchar* weights,
				__constant char* input, __global long* current, __global int* counter,
				__global double* neuronalDynVar, __global uchar* actionPotentials, __constant double* simulationParameters)
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

  float result = dot(coeffVec, weightsVec) * 32768.0f;

  // OpenCL 1.1 doesn't support atomic operations on float, so we cast
  // the result to long
  long resultLong = convert_long(result);
  long increment = result > 0 ? resultLong : (-resultLong);

  // Increment the synaptic current
  atom_add(&current[workId], increment);

  // The counter is used to count the synapses that have been served
  atomic_inc(&counter[workId]);

  mem_fence(CLK_LOCAL_MEM_FENCE); 

  // Proceed inside the block only when the last synapse has been served
  if (counter[workId] == get_local_size(0))
    {

      double unsignedCurrentDouble = convert_double(current[workId]) / 32768000.0f;
      double currentDouble = current[workId] > 0 ? unsignedCurrentDouble : (-unsignedCurrentDouble);

      // Compute the potential using Euler integration and Izhikevich model
      potential += 0.5f * (0.04f * pown(potential, 2) + 5.0f * potential + 140.0f - recovery + currentDouble);

      // Uncomment the following to use inverse Euler for the recovery variable
      
      /*
      recovery = (0.5f * 0.02f * 0.2f * potential + recovery) / (1.0f + 0.5f * 0.02f);      
      recovery += 0.5f * 0.02f * (0.2f * potential - recovery);
      */

      // Kahaman compensation algorithm to improve stability of recovery variable
      double y = 0.5f * a * (b * potential - recovery) - kahanCompensation;
      double t = recovery + y;
      kahanCompensation = (t - recovery) - y;
      recovery = t;
                        
      if (potential >= 30.0f)
	{
	  actionPotentials[(ushort)(workId / 8)] |= (1 << (workId - (ushort)(workId / 8) * 8));
	  recovery += d;
	  potential = c;
	}
      else
	{
	  actionPotentials[(ushort)(workId / 8)] &= ~(1 << (workId - (ushort)(workId / 8) * 8));
	}      

      current[workId] = 0;
      counter[workId] = 0;
    }
}
    
  

