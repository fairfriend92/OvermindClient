__kernel void mac_kernel_vec4(__global float* restrict coefficient,
			      __global int* restrict spike,
			      __global float* restrict output)
{
  // Set i to be the ID of the kernel instance.
  int i = get_global_id(0);

  int4 spike4 = vload4(i, spike);
  float4 coefficient4 = vload4(i, coefficient);
  float4 spikeFloat4 = convert_float4(spike4);
  output[i] = dot(spikeFloat4 * coefficient4, (float4)(1.0f, 1.0f, 1.0f, 1.0f));
}
  
