//#pragma OPENCL EXTENSION cl_khr_fp16 : enable
#pragma OPENCL EXTENSION cl_khr_fp64 : enable
#pragma OPENCL EXTENSION cl_khr_int64_base_atomics: enable

#define potential neuronalDynVar[workId * 2]
#define recovery neuronalDynVar[workId * 2 + 1]
  
__kernel void simulate_dynamics(__constant float* coeff, __constant uchar* weights,
				__constant uchar* input, __global long* current, __global int* counter,
				__global double* neuronalDynVar, __global uchar* actionPotentials)
{

  ushort localId = get_local_id(0);
  ushort workId = get_group_id(0);

  uchar16 index = vload16(localId, input);

  float4 coeffVec = (float4)(coeff[index.s0] + coeff[index.s1] + coeff[index.s2] + coeff[index.s3],
			     coeff[index.s4] + coeff[index.s5] + coeff[index.s6] + coeff[index.s7],
			     coeff[index.s8] + coeff[index.s9] + coeff[index.sa] + coeff[index.sb],
			     coeff[index.sc] + coeff[index.sd] + coeff[index.se] + coeff[index.sf]);
  
  float4 weightsVec = convert_float4(vload4(localId, weights + workId * 1024));

  float result = dot(coeffVec, weightsVec) * 32768.0f;

  long resultLong = convert_long(result);
  long increment = result > 0 ? resultLong : (-resultLong);
  
  atom_add(&current[workId], increment);
   
  atomic_inc(&counter[workId]);

  mem_fence(CLK_LOCAL_MEM_FENCE); 
 
  if (counter[workId] == get_local_size(0))
    {

      double unsignedCurrentDouble = convert_double(current[workId]) / 32768000.0f;
      double currentDouble = current[workId] > 0 ? unsignedCurrentDouble : (-unsignedCurrentDouble);
      
      potential += 0.5f * (0.04f * pown(potential, 2) + 5.0f * potential + 140.0f - recovery + currentDouble);      

      recovery = (0.5f * 0.1f * 0.2f * potential + recovery) / (1.0f + 0.5f * 0.1f);      
	
      if (potential >= 30.0f)
	{
	  actionPotentials[(ushort)(workId / 8)] |= (1 << (workId - (ushort)(workId / 8) * 8));
	  recovery += 2.0f;
	  potential = -65.0f;
	}
      else
	{
	  actionPotentials[(ushort)(workId / 8)] &= ~(1 << (workId - (ushort)(workId / 8) * 8));
	}      

      current[workId] = 0;
      counter[workId] = 0;
    }
}
    
  

