/* This kernel is for devices with 128 bits wide registers. 
   It computes 4 synapses at a time. */

#define FLOAT_VECTOR_WIDTH 4

__kernel __attribute__((vec_type_hint(float4)))
void simulate_dynamics(__constant float* restrict coeff, __global float* restrict weights,
		       __global char* restrict input, volatile __global int* restrict current,
		       __constant int* restrict localSize) // TODO: numOfSynapses and coeff can be __const if # of registers is sufficient
{

  //ushort localId = get_local_id(0); // Synapse vector ID
  //ushort workId = get_group_id(0); // Neuron ID
  ushort workId = get_global_id(0) / localSize[0];
  ushort localId = get_global_id(0) - workId * localSize[0];
  //ushort localSize = get_local_size(0);

  char16 index = vload16(localId, input);

  // Coefficients of the synapse kernel
  float4 coeffVec = (float4)(coeff[index.s0] + coeff[index.s1] + coeff[index.s2] + coeff[index.s3],
			     coeff[index.s4] + coeff[index.s5] + coeff[index.s6] + coeff[index.s7],
			     coeff[index.s8] + coeff[index.s9] + coeff[index.sa] + coeff[index.sb],
			     coeff[index.sc] + coeff[index.sd] + coeff[index.se] + coeff[index.sf]);
  // Synaptic weights 
  float4 weightsVec = vload4(localId, weights + workId * (localSize[0] * 4));

  float result = dot(coeffVec, weightsVec) * 32768.0f;

  // OpenCL 1.1 doesn't support atomic operations on float, so we cast
  // the result to long
  int resultInt = convert_int_rte(result);
  int increment = result > 0 ? resultInt : (-resultInt);

  // Increment the synaptic current
  atomic_add(&current[workId], increment);

}
    
  

