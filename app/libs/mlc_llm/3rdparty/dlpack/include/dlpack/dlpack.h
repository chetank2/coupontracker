#ifndef DLPACK_DLPACK_H_
#define DLPACK_DLPACK_H_

#ifdef __cplusplus
extern "C" {
#endif

#include <stdint.h>
#include <stddef.h>

/*! \brief Device types */
typedef enum {
  kDLCPU = 1,
  kDLGPU = 2,
  kDLCPUPinned = 3,
  kDLOpenCL = 4,
  kDLVulkan = 7,
  kDLMetal = 8,
  kDLVPI = 9,
  kDLROCM = 10,
  kDLExtDev = 12,
} DLDeviceType;

/*! \brief Device context */
typedef struct {
  DLDeviceType device_type;
  int device_id;
} DLDevice;

/*! \brief Data types */
typedef enum {
  kDLInt = 0U,
  kDLUInt = 1U,
  kDLFloat = 2U,
  kDLBfloat = 4U,
} DLDataTypeCode;

/*! \brief Data type */
typedef struct {
  uint8_t code;
  uint8_t bits;
  uint16_t lanes;
} DLDataType;

/*! \brief Tensor structure */
typedef struct {
  void* data;
  DLDevice device;
  int ndim;
  DLDataType dtype;
  int64_t* shape;
  int64_t* strides;
  uint64_t byte_offset;
} DLTensor;

/*! \brief Managed tensor with deleter */
typedef struct DLManagedTensor {
  DLTensor dl_tensor;
  void* manager_ctx;
  void (*deleter)(struct DLManagedTensor* self);
} DLManagedTensor;

#ifdef __cplusplus
}
#endif

#endif  // DLPACK_DLPACK_H_
