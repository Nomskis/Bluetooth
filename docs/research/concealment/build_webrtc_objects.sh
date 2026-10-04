#!/bin/sh
# Builds WebRTC 6367's NetEq + Opus decoder standalone, plus a driver.
set -e
cd "$(dirname "$0")"
OUT=out
mkdir -p $OUT
CFLAGS="-O2 -fPIC -DWEBRTC_POSIX -DWEBRTC_LINUX -DNDEBUG -DWEBRTC_APM_DEBUG_DUMP=0 -DWEBRTC_ENABLE_PROTOBUF=0 -DWEBRTC_OPUS_SUPPORT_120MS_PTIME=1 -DWEBRTC_OPUS_VARIABLE_COMPLEXITY=0 -DRTC_ENABLE_WIN_WGC=0 -DWEBRTC_ARCH_LITTLE_ENDIAN -I. -Ithird_party/abseil-cpp -w"
CXXFLAGS="$CFLAGS -std=c++17"

SRCS_C="$(ls common_audio/signal_processing/*.c | grep -v -E "_mips|_neon|_arm") common_audio/third_party/spl_sqrt_floor/spl_sqrt_floor.c"
SRCS_CC="
$(ls modules/audio_coding/neteq/*.cc | grep -v -E 'unittest|_test\.cc')
$(ls api/neteq/*.cc | grep -v unittest)
api/audio_codecs/audio_decoder.cc
api/audio_codecs/audio_format.cc
api/audio_codecs/audio_codec_pair_id.cc
api/audio/audio_frame.cc
api/audio/channel_layout.cc
api/rtp_packet_info.cc
api/rtp_headers.cc
api/units/time_delta.cc
api/units/timestamp.cc
api/units/data_rate.cc
api/units/data_size.cc
api/units/frequency.cc
modules/audio_coding/codecs/opus/audio_decoder_opus.cc
modules/audio_coding/codecs/opus/opus_interface.cc
modules/audio_coding/codecs/opus/audio_coder_opus_common.cc
modules/audio_coding/codecs/cng/webrtc_cng.cc
common_audio/signal_processing/dot_product_with_scale.cc
rtc_base/checks.cc
rtc_base/logging.cc
rtc_base/string_encode.cc
rtc_base/string_to_number.cc
rtc_base/string_utils.cc
rtc_base/strings/string_builder.cc
rtc_base/experiments/field_trial_parser.cc
rtc_base/experiments/field_trial_units.cc
rtc_base/experiments/struct_parameters_parser.cc
rtc_base/time_utils.cc
rtc_base/system_time.cc
rtc_base/platform_thread_types.cc
rtc_base/race_checker.cc
rtc_base/synchronization/sequence_checker_internal.cc
rtc_base/synchronization/yield_policy.cc
rtc_base/numerics/histogram_percentile_counter.cc
rtc_base/numerics/sample_counter.cc
rtc_base/numerics/event_based_exponential_moving_average.cc
rtc_base/numerics/exp_filter.cc
system_wrappers/source/field_trial.cc
system_wrappers/source/metrics.cc
system_wrappers/source/clock.cc
rtc_base/event.cc
rtc_base/platform_thread.cc
third_party/abseil-cpp/absl/strings/internal/memutil.cc
rtc_base/strings/audio_format_to_string.cc
api/video/color_space.cc
api/video/hdr_metadata.cc
rtc_base/event_tracer.cc
api/task_queue/task_queue_base.cc
third_party/abseil-cpp/absl/strings/match.cc
third_party/abseil-cpp/absl/strings/ascii.cc
"
OBJS=""
for f in $SRCS_C; do
  o=$OUT/$(echo $f | tr '/' '_').o
  [ $o -nt $f ] || gcc $CFLAGS -c $f -o $o
  OBJS="$OBJS $o"
done
for f in $SRCS_CC; do
  [ -f "$f" ] || { echo "missing $f"; continue; }
  o=$OUT/$(echo $f | tr '/' '_').o
  [ $o -nt $f ] || g++ $CXXFLAGS -c $f -o $o
  OBJS="$OBJS $o"
done
echo "$OBJS" > $OUT/objs.txt
