__kernel void mac_kernel_vec4(__global float* restrict coefficient,
			      __global bool* restrict spike,
			      __global fool* restrict previousOutput,
			      __global float* restrict output)
{
  // Set i to be the ID of the kernel instance.
  int i = get_global_id(0);

  // Load vector registers
  float4 spike4 = vload4(i, spike);
  float4 previousOutput4 = vload4(i, previousOutput);

  // Store the result 
  vstore4(spike4 * coefficient + previousOutput4, i, output);
}
  
