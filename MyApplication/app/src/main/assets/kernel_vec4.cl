/* This kernel is for devices with 128 bits wide registers. 
   It computes 4 synapses at a time. */

#pragma OPENCL EXTENSION cl_khr_fp64 : enable
#pragma OPENCL EXTENSION cl_khr_int64_base_atomics: enable

__kernel __attribute__((work_group_size_hint(256, 1, 1))) __attribute__((vec_type_hint(float4)))
void simulate_dynamics(__global float* restrict coeff, __global float* restrict weights,
		       __global char* restrict input, __global long* restrict current)
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
  float4 weightsVec = vload4(localId, weights + workId * 1024);

  float result = dot(coeffVec, weightsVec);

  // OpenCL 1.1 doesn't support atomic operations on float, so we cast
  // the result to long
  long resultLong = convert_long(result);
  long increment = result > 0 ? resultLong : (-resultLong);
  increment <<= 15;

  // Increment the synaptic current
  atom_add(&current[workId], increment);

}
    
  

