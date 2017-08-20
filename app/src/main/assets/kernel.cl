/* This kernel is for devices with 32 bits wide register. 
   It computes one synapse at a time. */

__kernel __attribute__((work_group_size_hint(256, 1, 1))) __attribute__((vec_type_hint(float4)))
void simulate_dynamics(__global float* restrict coeff, __global float* restrict weights,
		       __global char* restrict input, volatile __global int* restrict current)
{

  ushort localId = get_local_id(0);
  ushort workId = get_group_id(0);
  ushort localSize = get_local_size(0);

  char4 index = vload4(localId, input);

  // Coefficients of the synapse kernel
  float localCoeff = (float)(coeff[index.s0] + coeff[index.s1] + coeff[index.s2] + coeff[index.s3]);
  
  // Synaptic weights 
  float localWeights = weights[localId + workId * localSize];

  float result = localCoeff * localWeights * 32768.0f;

  int resultInt = convert_int_rte(result);
  int increment = result > 0 ? resultInt : (-resultInt);

  // Increment the synaptic current
  atomic_add(&current[workId], increment);

}
    
  

