#ifndef TFLITEENGINE_H_
#define TFLITEENGINE_H_

#include <string>
#include <vector>

class TFLiteEngine {
public:
    TFLiteEngine() = default;
    ~TFLiteEngine() = default;

    int initVocab(bool isMultilingual);
    std::vector<float> computeMel(std::vector<float> samples);
    std::string decodeTokens(const std::vector<int> &tokens);
};

#endif // TFLITEENGINE_H_
