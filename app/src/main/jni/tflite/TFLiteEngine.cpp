#include <iostream>
#include <fstream>
#include <cstring>
#include <vector>
#include <thread>
#include <memory>
#include <sys/time.h>
#include <algorithm>

#include "TFLiteEngine.h"
#include "input_features.h"
#include "filters_vocab_multilingual.h"
#include "filters_vocab_en.h"
#include "whisper.h"

#define TIME_DIFF_MS(start, end) (((end.tv_sec - start.tv_sec) * 1000000) + (end.tv_usec - start.tv_usec))/1000

static whisper_filters filters;
static whisper_mel mel;

int TFLiteEngine::initVocab(bool isMultilingual) {
    const char *vocabData = isMultilingual
                            ? reinterpret_cast<const char *>(filters_vocab_multilingual)
                            : reinterpret_cast<const char *>(filters_vocab_en);

    int magic = 0;
    std::memcpy(&magic, vocabData, sizeof(magic));
    vocabData += sizeof(magic);
    if (magic != 0x57535052) { // 'WSPR'
        std::cerr << "Invalid vocab data (bad magic)" << std::endl;
        return -1;
    }

    std::memcpy(&filters.n_mel, vocabData, sizeof(filters.n_mel));
    vocabData += sizeof(filters.n_mel);
    std::memcpy(&filters.n_fft, vocabData, sizeof(filters.n_fft));
    vocabData += sizeof(filters.n_fft);

    filters.data.resize(filters.n_mel * filters.n_fft);
    std::memcpy(filters.data.data(), vocabData, filters.data.size() * sizeof(float));
    vocabData += filters.data.size() * sizeof(float);

    int n_vocab = 0;
    std::memcpy(&n_vocab, vocabData, sizeof(n_vocab));
    vocabData += sizeof(n_vocab);

    for (int i = 0; i < n_vocab; i++) {
        int len = 0;
        std::memcpy(&len, vocabData, sizeof(len));
        vocabData += sizeof(len);

        std::string word(vocabData, len);
        vocabData += len;

        g_vocab.id_to_token[i] = word;
    }

    int n_vocab_additional = 51864;
    if (isMultilingual) {
        n_vocab_additional = 51865;
        g_vocab.n_vocab = 51865;
        g_vocab.token_eot++;
        g_vocab.token_sot++;
        g_vocab.token_prev++;
        g_vocab.token_solm++;
        g_vocab.token_not++;
        g_vocab.token_beg++;
    }

    for (int i = n_vocab; i < n_vocab_additional; i++) {
        std::string word;
        if (i > g_vocab.token_beg) {
            word = "[_TT_" + std::to_string(i - g_vocab.token_beg) + "]";
        } else if (i == g_vocab.token_eot) {
            word = "[_EOT_]";
        } else if (i == g_vocab.token_sot) {
            word = "[_SOT_]";
        } else if (i == g_vocab.token_prev) {
            word = "[_PREV_]";
        } else if (i == g_vocab.token_not) {
            word = "[_NOT_]";
        } else if (i == g_vocab.token_beg) {
            word = "[_BEG_]";
        } else {
            word = "[_extra_token_" + std::to_string(i) + "]";
        }
        g_vocab.id_to_token[i] = word;
    }

    return 0;
}

std::vector<float> TFLiteEngine::computeMel(std::vector<float> samples) {
    samples.resize(WHISPER_SAMPLE_RATE * WHISPER_CHUNK_SIZE, 0.0f);
    const auto processor_count = std::max(2u, std::thread::hardware_concurrency());

    log_mel_spectrogram(samples.data(), samples.size(), WHISPER_SAMPLE_RATE, WHISPER_N_FFT,
                        WHISPER_HOP_LENGTH, WHISPER_N_MEL, processor_count, filters, mel);

    return mel.data;
}

std::string TFLiteEngine::decodeTokens(const std::vector<int> &tokens) {
    std::string text;
    for (int token : tokens) {
        if (token == g_vocab.token_eot) break;
        if (token < g_vocab.token_eot) {
            text += whisper_token_to_str(token);
        }
    }
    return text;
}
