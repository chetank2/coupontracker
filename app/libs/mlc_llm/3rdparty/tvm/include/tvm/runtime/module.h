#ifndef TVM_RUNTIME_MODULE_H_
#define TVM_RUNTIME_MODULE_H_

#include <string>
#include <memory>
#include <unordered_map>

namespace tvm {
namespace runtime {

/*!
 * \brief TVM Module interface
 */
class Module {
 public:
  /*! \brief Virtual destructor */
  virtual ~Module() = default;

  /*!
   * \brief Get function from module
   * \param name Function name
   * \return Function pointer or nullptr if not found
   */
  virtual void* GetFunction(const std::string& name) = 0;

  /*!
   * \brief Check if function exists
   * \param name Function name
   * \return True if function exists
   */
  virtual bool HasFunction(const std::string& name) = 0;

  /*!
   * \brief Get module type
   * \return Module type string
   */
  virtual std::string type_key() const = 0;
};

/*!
 * \brief Packed function interface
 */
class PackedFunc {
 public:
  /*! \brief Function pointer type */
  typedef void (*FunctionPtr)(void* args, int num_args);

  /*! \brief Default constructor */
  PackedFunc() : func_ptr_(nullptr) {}

  /*! \brief Constructor with function pointer */
  explicit PackedFunc(FunctionPtr func_ptr) : func_ptr_(func_ptr) {}

  /*!
   * \brief Call function
   * \param args Arguments
   * \param num_args Number of arguments
   */
  void operator()(void* args, int num_args) const {
    if (func_ptr_) {
      func_ptr_(args, num_args);
    }
  }

  /*! \brief Check if function is valid */
  bool defined() const { return func_ptr_ != nullptr; }

 private:
  FunctionPtr func_ptr_;
};

/*!
 * \brief Load module from file
 * \param file_name Module file path
 * \param format Module format
 * \return Loaded module
 */
std::shared_ptr<Module> LoadModule(const std::string& file_name, const std::string& format = "");

}  // namespace runtime
}  // namespace tvm

#endif  // TVM_RUNTIME_MODULE_H_
