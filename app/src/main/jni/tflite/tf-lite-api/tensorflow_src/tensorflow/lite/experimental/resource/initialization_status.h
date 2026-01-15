#ifndef TFLITE_EXPERIMENTAL_RESOURCE_INITIALIZATION_STATUS_H_
#define TFLITE_EXPERIMENTAL_RESOURCE_INITIALIZATION_STATUS_H_

#include <map>
#include "tensorflow/lite/c/common.h"

namespace tflite {
namespace resource {

// Minimal stand-in for missing upstream header. Tracks initialization status per subgraph.
using InitializationStatusMap = std::map<int, TfLiteStatus>;

}  // namespace resource
}  // namespace tflite

#endif  // TFLITE_EXPERIMENTAL_RESOURCE_INITIALIZATION_STATUS_H_
