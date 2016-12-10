__kernel void mac_kernel_vec4(__global float* restrict coefficient,
			      __global int* restrict input,
			      __global float* restrict output)
{
  // Set i to be the ID of the kernel instance.
  int i = get_global_id(0);

  int4 input4 = vload4(i, input);
  float4 coeff4 = (float4)(input[input4.x], input[input4.y], input[input4.z], input[input4.w]);
  output[i] = dot(coeff4, (float4)(1.0f, 1.0f, 1.0f, 1.0f));
  vstore4(input4, i, input);


  // Classic kernel, uncomment initialization code in native_method.cpp too
  /* 
  int4 spike4 = vload4(i, spike);
  float4 coefficient4 = vload4(i, coefficient);
  float4 spikeFloat4 = convert_float4(spike4);
  output[i] = dot(spikeFloat4 * coefficient4, (float4)(1.0f, 1.0f, 1.0f, 1.0f));
  */
}
  
