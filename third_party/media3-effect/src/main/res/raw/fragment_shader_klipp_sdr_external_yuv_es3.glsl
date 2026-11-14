#version 300 es
// Klipp (Apache 2.0, like the rest of the module): SDR video sampled as YUV (EXT_YUV_target) and converted with the
// file's own matrix and range (KlippYuv), not clamped – a camera's super-whites and sub-blacks reach the effects.
// Otherwise as fragment_shader_transformation_sdr_external_es2.glsl.

#extension GL_OES_EGL_image_external : require
#extension GL_EXT_YUV_target : require
precision highp float;
uniform __samplerExternal2DY2YEXT uTexSampler;
uniform mat3 uYuvToRgbColorTransform;
uniform vec3 uYuvOffset;
uniform mat4 uRgbMatrix;
// C.java#ColorTransfer value: COLOR_TRANSFER_LINEAR or COLOR_TRANSFER_SDR_VIDEO.
uniform int uOutputColorTransfer;
uniform int uSdrWorkingColorSpace;
in vec2 vTexSamplingCoord;
out vec4 outColor;

const float inverseGamma = 0.4500;
const float gamma = 1.0 / inverseGamma;
const int WORKING_COLOR_SPACE_DEFAULT = 0;
const int WORKING_COLOR_SPACE_ORIGINAL = 1;
const int WORKING_COLOR_SPACE_LINEAR = 2;
const vec3 ERROR_COLOR_BLUE = vec3(0.0, 0.0, 1.0);
const vec3 ERROR_COLOR_RED = vec3(1.0, 0.0, 0.0);

// SMPTE 170M, mirrored below zero (values outside 0..1 are kept)
float smpte170mEotfSingleChannel(float e) {
  float a = abs(e);
  float l = a < 0.0812 ? a / 4.500 : pow((a + 0.099) / 1.099, gamma);
  return sign(e) * l;
}
vec3 smpte170mEotf(vec3 c) {
  return vec3(smpte170mEotfSingleChannel(c.r), smpte170mEotfSingleChannel(c.g), smpte170mEotfSingleChannel(c.b));
}
float smpte170mOetfSingleChannel(float o) {
  float a = abs(o);
  float e = a < 0.018 ? a * 4.500 : 1.099 * pow(a, inverseGamma) - 0.099;
  return sign(o) * e;
}
vec3 smpte170mOetf(vec3 c) {
  return vec3(smpte170mOetfSingleChannel(c.r), smpte170mOetfSingleChannel(c.g), smpte170mOetfSingleChannel(c.b));
}

vec3 convertToWorkingColors(vec3 inputColor) {
  if (uSdrWorkingColorSpace == WORKING_COLOR_SPACE_DEFAULT ||
      uSdrWorkingColorSpace == WORKING_COLOR_SPACE_ORIGINAL) {
    return inputColor;
  } else if (uSdrWorkingColorSpace == WORKING_COLOR_SPACE_LINEAR) {
    return smpte170mEotf(inputColor);
  } else {
    return ERROR_COLOR_BLUE;
  }
}

vec3 convertToOutputColors(vec3 workingColors) {
  const int COLOR_TRANSFER_LINEAR = 1;
  const int COLOR_TRANSFER_SDR_VIDEO = 3;
  if (uSdrWorkingColorSpace == WORKING_COLOR_SPACE_DEFAULT) {
    if (uOutputColorTransfer == COLOR_TRANSFER_LINEAR) {
      return smpte170mEotf(workingColors);
    } else if (uOutputColorTransfer == COLOR_TRANSFER_SDR_VIDEO) {
      return workingColors;
    } else {
      return ERROR_COLOR_RED;
    }
  } else if (uSdrWorkingColorSpace == WORKING_COLOR_SPACE_ORIGINAL) {
    return workingColors;
  } else if (uSdrWorkingColorSpace == WORKING_COLOR_SPACE_LINEAR) {
    if (uOutputColorTransfer == COLOR_TRANSFER_LINEAR) {
      return workingColors;
    } else if (uOutputColorTransfer == COLOR_TRANSFER_SDR_VIDEO) {
      return smpte170mOetf(workingColors);
    } else {
      return ERROR_COLOR_RED;
    }
  } else {
    return ERROR_COLOR_RED;
  }
}

void main() {
  vec3 yuv = texture(uTexSampler, vTexSamplingCoord).xyz;
  vec3 rgb = uYuvToRgbColorTransform * (yuv - uYuvOffset);
  vec3 workingColors = convertToWorkingColors(rgb);
  vec4 transformedColors = uRgbMatrix * vec4(workingColors, 1.0);
  outColor = vec4(convertToOutputColors(transformedColors.rgb), 1.0);
}
