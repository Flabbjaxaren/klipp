// Only for linking: ggml's OpenCL part links against libOpenCL.so, but the file isn't shipped with the app – the phone's own
// (Qualcomm's driver, /vendor/lib64/libOpenCL.so) is loaded when the app runs. If it doesn't exist, the OpenCL part isn't loaded and
// speech recognition runs on the CPU. The functions here are never called.
void clBuildProgram(void) {}
void clCreateBuffer(void) {}
void clCreateBufferWithProperties(void) {}
void clCreateCommandQueue(void) {}
void clCreateContext(void) {}
void clCreateImage(void) {}
void clCreateKernel(void) {}
void clCreateProgramWithBinary(void) {}
void clCreateProgramWithSource(void) {}
void clCreateSubBuffer(void) {}
void clEnqueueBarrierWithWaitList(void) {}
void clEnqueueCopyBuffer(void) {}
void clEnqueueFillBuffer(void) {}
void clEnqueueMarkerWithWaitList(void) {}
void clEnqueueNDRangeKernel(void) {}
void clEnqueueReadBuffer(void) {}
void clEnqueueWriteBuffer(void) {}
void clFinish(void) {}
void clFlush(void) {}
void clGetDeviceIDs(void) {}
void clGetDeviceInfo(void) {}
void clGetEventProfilingInfo(void) {}
void clGetKernelInfo(void) {}
void clGetKernelSubGroupInfo(void) {}
void clGetKernelWorkGroupInfo(void) {}
void clGetPlatformIDs(void) {}
void clGetPlatformInfo(void) {}
void clGetProgramBuildInfo(void) {}
void clGetProgramInfo(void) {}
void clReleaseEvent(void) {}
void clReleaseKernel(void) {}
void clReleaseMemObject(void) {}
void clReleaseProgram(void) {}
void clSetKernelArg(void) {}
void clWaitForEvents(void) {}
