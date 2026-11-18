/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.messenger.asr;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Catalog of the speech recognition models easygram can download. Every file is checked against its size and
 * SHA-256 after download; its URLs are tried in order (easygram's own release mirror first, then the upstream
 * sherpa-onnx copy on Hugging Face where one exists).
 *
 * To publish the mirror run {@code python3 Tools/asr_models_mirror.py} and upload its output to the
 * {@value #MIRROR_TAG} release.
 */
public final class EasygramAsrModels {

    public static final String LANG_RU = "ru";
    public static final String LANG_EN = "en";
    /** Not a language: the spoken language identification model, used to pick the RU / EN model by the voice. */
    public static final String LANG_ID = "lid";

    public static final int TIER_LITE = 0, TIER_OPTIMAL = 1, TIER_MAX = 2;

    /** Which sherpa-onnx recognizer runs the model. */
    public static final int ENGINE_NEMO_CTC = 0, ENGINE_TONE_CTC = 1, ENGINE_MOONSHINE_V2 = 2, ENGINE_NEMO_TRANSDUCER = 3,
            ENGINE_WHISPER_LID = 4;

    public static final String LID_MODEL_ID = "lid-whisper-tiny";

    static final String MIRROR_TAG = "asr-models-v1";
    private static final String MIRROR = "https://github.com/webarmour/Easygram/releases/download/" + MIRROR_TAG + "/";
    private static final String HF = "https://huggingface.co/csukuangfj/";

    public static final class ModelFile {
        public final String name;
        public final long size;
        public final String sha256;
        public final String[] urls;

        ModelFile(String name, long size, String sha256, String... urls) {
            this.name = name;
            this.size = size;
            this.sha256 = sha256;
            this.urls = urls;
        }
    }

    public static final class Model {
        public final String id;
        public final String lang;
        public final int tier;
        public final int engine;
        /** Short model name shown in settings, e.g. "GigaAM v3". */
        public final String title;
        public final boolean punctuation;
        public final String license;
        public final ModelFile[] files;

        Model(String id, String lang, int tier, int engine, String title, boolean punctuation, String license, ModelFile... files) {
            this.id = id;
            this.lang = lang;
            this.tier = tier;
            this.engine = engine;
            this.title = title;
            this.punctuation = punctuation;
            this.license = license;
            this.files = files;
        }

        public long totalSize() {
            long sum = 0;
            for (ModelFile f : files) {
                sum += f.size;
            }
            return sum;
        }

        @Nullable
        public ModelFile file(String name) {
            for (ModelFile f : files) {
                if (f.name.equals(name)) {
                    return f;
                }
            }
            return null;
        }
    }

    private static ModelFile mirrored(String model, String name, long size, String sha256, @Nullable String hfRepo) {
        String mirror = MIRROR + model + "--" + name;
        if (hfRepo == null) {
            return new ModelFile(name, size, sha256, mirror);
        }
        return new ModelFile(name, size, sha256, mirror, HF + hfRepo + "/resolve/main/" + name);
    }

    private static final List<Model> ALL;

    static {
        ArrayList<Model> list = new ArrayList<>();

        // Russian ----------------------------------------------------------------------------------------------
        String tone = "sherpa-onnx-streaming-t-one-russian-2025-09-08";
        list.add(new Model("ru-t-one", LANG_RU, TIER_LITE, ENGINE_TONE_CTC, "T-one", false, "Apache-2.0",
                mirrored("ru-t-one", "model.onnx", 144193702L, "5ded080e2a6c86ecc11bcb0902d77524eb3e8b0844cb0c0754347f5aafb4dabc", tone),
                mirrored("ru-t-one", "tokens.txt", 202L, "27f7b3ba2096c572375fba1a6b29af1f80d86e08a329940612908112695f97e0", tone)));

        String giga = "sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16";
        list.add(new Model("ru-gigaam-v3", LANG_RU, TIER_OPTIMAL, ENGINE_NEMO_CTC, "GigaAM v3", true, "MIT",
                mirrored("ru-gigaam-v3", "model.int8.onnx", 224893661L, "d5fea8df94263c285e54b21e5774b707c707192d3bdbeffd7b1eb07fb6743b35", giga),
                mirrored("ru-gigaam-v3", "tokens.txt", 2007L, "142de7570b3de5b3035ce111a89c228e80e6085273731d944093ddf24fa539cd", giga)));

        // English ----------------------------------------------------------------------------------------------
        // Moonshine v2 (2026-02-27) exists upstream only as a .tar.bz2, so these files come from the mirror alone.
        list.add(new Model("en-moonshine-tiny", LANG_EN, TIER_LITE, ENGINE_MOONSHINE_V2, "Moonshine Tiny", true, "MIT",
                mirrored("en-moonshine-tiny", "encoder_model.ort", 13281600L, "94e90a4654fc45cdfedb77c4c08e1739f48862998e58fada384b25118134f221", null),
                mirrored("en-moonshine-tiny", "decoder_model_merged.ort", 30412256L, "cf524c4862d36e9e5ab032eddc73637efd822d70e868ac575cf1a46e1e4708a0", null),
                mirrored("en-moonshine-tiny", "tokens.txt", 549350L, "2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049", null)));

        list.add(new Model("en-moonshine-base", LANG_EN, TIER_OPTIMAL, ENGINE_MOONSHINE_V2, "Moonshine Base", true, "MIT",
                mirrored("en-moonshine-base", "encoder_model.ort", 31326816L, "7c66495948d0d08ec1af454cd4b5514862ae6511e94712a60e6d83eaec8dc8cf", null),
                mirrored("en-moonshine-base", "decoder_model_merged.ort", 109424400L, "d9d7b333af34bc552580576ddcf248a1c6c839e0d3b43b09afb9376ed009899d", null),
                mirrored("en-moonshine-base", "tokens.txt", 549350L, "2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049", null)));

        String parakeet = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8";
        list.add(new Model("en-parakeet-v2", LANG_EN, TIER_MAX, ENGINE_NEMO_TRANSDUCER, "Parakeet TDT 0.6B v2", true, "CC-BY-4.0",
                mirrored("en-parakeet-v2", "encoder.int8.onnx", 652282309L, "92a10bd9c23936ea0566c29f963bc57c2a5df4261b6a8921c39abea66557ca8b", parakeet),
                mirrored("en-parakeet-v2", "decoder.int8.onnx", 7257753L, "4d097bae3bac5c96018d5293ba39f1b657296102b142affda40378f38b020f41", parakeet),
                mirrored("en-parakeet-v2", "joiner.int8.onnx", 1739080L, "77a29f3e5ac7d37074e0af465391b80f314015d5b97f0f90c6c7767f1d56f428", parakeet),
                mirrored("en-parakeet-v2", "tokens.txt", 9384L, "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d", parakeet)));

        // Spoken language identification ------------------------------------------------------------------------
        String whisper = "sherpa-onnx-whisper-tiny";
        list.add(new Model(LID_MODEL_ID, LANG_ID, TIER_LITE, ENGINE_WHISPER_LID, "Whisper tiny", false, "MIT",
                mirrored("lid-whisper-tiny", "tiny-encoder.int8.onnx", 12937772L, "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434", whisper),
                mirrored("lid-whisper-tiny", "tiny-decoder.int8.onnx", 89855401L, "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925", whisper)));

        ALL = Collections.unmodifiableList(list);
    }

    public static List<Model> all() {
        return ALL;
    }

    public static List<Model> forLang(String lang) {
        ArrayList<Model> out = new ArrayList<>();
        for (Model m : ALL) {
            if (m.lang.equals(lang)) {
                out.add(m);
            }
        }
        return out;
    }

    @Nullable
    public static Model byId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (Model m : ALL) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        return null;
    }

    private EasygramAsrModels() {
    }
}
