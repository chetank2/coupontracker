#ifndef MLC_RUNTIME_C_RUNTIME_API_H_
#define MLC_RUNTIME_C_RUNTIME_API_H_

#ifdef __cplusplus
extern "C" {
#endif

#include <stdint.h>
#include <stddef.h>

/*! \brief MLC runtime handle */
typedef void* MLCRuntimeHandle;

/*! \brief MLC model handle */
typedef void* MLCModelHandle;

/*! \brief MLC tensor handle */
typedef void* MLCTensorHandle;

/*! \brief Return codes for MLC runtime functions */
typedef enum {
    MLC_SUCCESS = 0,
    MLC_ERROR_INVALID_ARGUMENT = -1,
    MLC_ERROR_OUT_OF_MEMORY = -2,
    MLC_ERROR_RUNTIME_ERROR = -3,
    MLC_ERROR_MODEL_NOT_FOUND = -4,
    MLC_ERROR_INFERENCE_FAILED = -5
} MLCReturnCode;

/*! \brief Device types */
typedef enum {
    MLC_DEVICE_CPU = 1,
    MLC_DEVICE_GPU = 2,
    MLC_DEVICE_VULKAN = 10
} MLCDeviceType;

/*! \brief Data types */
typedef enum {
    MLC_DTYPE_FLOAT32 = 0,
    MLC_DTYPE_FLOAT16 = 1,
    MLC_DTYPE_INT8 = 2,
    MLC_DTYPE_UINT8 = 3,
    MLC_DTYPE_INT32 = 4
} MLCDataType;

/*! \brief Tensor shape */
typedef struct {
    int64_t* data;
    size_t ndim;
} MLCShape;

/*!
 * \brief Initialize MLC runtime
 * \param device_type Device type to use
 * \param device_id Device ID (0 for default)
 * \return Runtime handle or NULL on failure
 */
MLCRuntimeHandle MLCRuntimeCreate(MLCDeviceType device_type, int device_id);

/*!
 * \brief Destroy MLC runtime
 * \param handle Runtime handle
 */
void MLCRuntimeDestroy(MLCRuntimeHandle handle);

/*!
 * \brief Load model from path
 * \param runtime Runtime handle
 * \param model_path Path to model directory
 * \param config_path Path to model config
 * \return Model handle or NULL on failure
 */
MLCModelHandle MLCModelLoad(MLCRuntimeHandle runtime, const char* model_path, const char* config_path);

/*!
 * \brief Destroy model
 * \param model Model handle
 */
void MLCModelDestroy(MLCModelHandle model);

/*!
 * \brief Run vision inference
 * \param model Model handle
 * \param image_data Raw image bytes (RGB format)
 * \param width Image width
 * \param height Image height
 * \param prompt Text prompt
 * \param output_buffer Output buffer for result
 * \param buffer_size Size of output buffer
 * \return Return code
 */
MLCReturnCode MLCModelRunVisionInference(
    MLCModelHandle model,
    const uint8_t* image_data,
    int width,
    int height,
    const char* prompt,
    char* output_buffer,
    size_t buffer_size
);

/*!
 * \brief Get model memory usage
 * \param model Model handle
 * \return Memory usage in bytes
 */
size_t MLCModelGetMemoryUsage(MLCModelHandle model);

/*!
 * \brief Check if model is loaded
 * \param model Model handle
 * \return 1 if loaded, 0 otherwise
 */
int MLCModelIsLoaded(MLCModelHandle model);

/*!
 * \brief Set inference parameters
 * \param model Model handle
 * \param temperature Sampling temperature
 * \param top_p Top-p sampling parameter
 * \param max_tokens Maximum tokens to generate
 * \return Return code
 */
MLCReturnCode MLCModelSetInferenceParams(
    MLCModelHandle model,
    float temperature,
    float top_p,
    int max_tokens
);

#ifdef __cplusplus
}
#endif

#endif  // MLC_RUNTIME_C_RUNTIME_API_H_
