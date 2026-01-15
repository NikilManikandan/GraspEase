#ifndef TFLITE_EXPERIMENTAL_RESOURCE_RESOURCE_BASE_H_
#define TFLITE_EXPERIMENTAL_RESOURCE_RESOURCE_BASE_H_

#include <map>
#include <memory>

namespace tflite {
namespace resource {

class ResourceBase {
public:
    virtual ~ResourceBase() = default;
};

using ResourceMap = std::map<int, std::unique_ptr<ResourceBase>>;

}  // namespace resource
}  // namespace tflite

#endif  // TFLITE_EXPERIMENTAL_RESOURCE_RESOURCE_BASE_H_
