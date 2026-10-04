// Real WebRTC 6367 NetEq + Opus decoder fed with Opus (optionally RED-wrapped,
// exactly as AudioEncoderCopyRed builds it) packets that went through a
// Gilbert-Elliott loss channel with jitter. Writes NetEq's 48 kHz output.
//
// usage: neteq_sim in.pcm seconds bitrate ptime_ms fec loss_perc red p_gb p_bg
//                  seed jitter_ms field_trials out.pcm
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <map>
#include <memory>
#include <random>
#include <vector>

#include "api/audio/audio_frame.h"
#include "api/audio_codecs/audio_decoder_factory.h"
#include "api/neteq/neteq.h"
#include "api/rtp_headers.h"
#include "modules/audio_coding/codecs/opus/audio_decoder_opus.h"
#include "modules/audio_coding/neteq/default_neteq_factory.h"
#include "system_wrappers/include/clock.h"
#include "system_wrappers/include/field_trial.h"
#include "third_party/opus/src/include/opus.h"

using namespace webrtc;

class OpusOnlyFactory : public AudioDecoderFactory {
 public:
  std::vector<AudioCodecSpec> GetSupportedDecoders() override { return {}; }
  bool IsSupportedDecoder(const SdpAudioFormat& f) override { return f.name == "opus"; }
  std::unique_ptr<AudioDecoder> MakeAudioDecoder(const SdpAudioFormat& f,
                                                 absl::optional<AudioCodecPairId>) override {
    if (f.name != "opus") return nullptr;
    return std::make_unique<AudioDecoderOpusImpl>(1, 48000);
  }
};

struct Pkt {
  int64_t arrival_ms;
  uint16_t seq;
  uint32_t ts;
  uint8_t pt;
  std::vector<uint8_t> payload;
};

int main(int argc, char** argv) {
  if (argc != 14) { fprintf(stderr, "bad args\n"); return 1; }
  const char* in = argv[1];
  double seconds = atof(argv[2]);
  int bitrate = atoi(argv[3]), ptime = atoi(argv[4]), fec = atoi(argv[5]), loss_perc = atoi(argv[6]);
  int red = atoi(argv[7]);
  double p_gb = atof(argv[8]), p_bg = atof(argv[9]);
  unsigned seed = (unsigned)atoi(argv[10]);
  int jitter_ms = atoi(argv[11]);
  static std::string trials = argv[12];
  field_trial::InitFieldTrialsFromString(trials.c_str());

  FILE* f = fopen(in, "rb");
  int total = (int)(seconds * 48000);
  std::vector<int16_t> pcm(total);
  total = (int)fread(pcm.data(), 2, total, f);
  fclose(f);
  const int n = ptime * 48;
  const int frames = total / n;

  int err;
  OpusEncoder* enc = opus_encoder_create(48000, 1, OPUS_APPLICATION_VOIP, &err);
  opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate));
  opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(fec));
  opus_encoder_ctl(enc, OPUS_SET_MAX_BANDWIDTH(OPUS_BANDWIDTH_FULLBAND));
  opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(5));
  opus_encoder_ctl(enc, OPUS_SET_DTX(0));
  opus_encoder_ctl(enc, OPUS_SET_PACKET_LOSS_PERC(loss_perc));
  opus_encoder_ctl(enc, OPUS_SET_VBR(1));

  const uint8_t kOpusPt = 111, kRedPt = 63;
  std::vector<std::vector<uint8_t>> enc_frames(frames);
  std::mt19937 rng(seed);
  std::uniform_real_distribution<double> U(0, 1);
  std::vector<Pkt> pkts;
  bool bad = false;
  int lost = 0;
  for (int k = 0; k < frames; k++) {
    uint8_t buf[1500];
    int len = opus_encode(enc, pcm.data() + k * n, n, buf, sizeof(buf));
    enc_frames[k].assign(buf, buf + len);
    Pkt p;
    p.seq = (uint16_t)k;
    p.ts = (uint32_t)(k * n);
    if (red > 0) {
      // RFC 2198 as AudioEncoderCopyRed: oldest redundant block first.
      int first = std::max(0, k - red);
      std::vector<uint8_t> hdr, body;
      for (int j = first; j < k; j++) {
        const auto& e = enc_frames[j];
        uint32_t off = (uint32_t)((k - j) * n);
        hdr.push_back(0x80 | kOpusPt);
        hdr.push_back((uint8_t)((off << 2) >> 8));
        hdr.push_back((uint8_t)(((off << 2) & 0xff) | (e.size() >> 8)));
        hdr.push_back((uint8_t)(e.size() & 0xff));
        body.insert(body.end(), e.begin(), e.end());
      }
      hdr.push_back(kOpusPt);
      p.payload = hdr;
      p.payload.insert(p.payload.end(), body.begin(), body.end());
      p.payload.insert(p.payload.end(), enc_frames[k].begin(), enc_frames[k].end());
      p.pt = kRedPt;
    } else {
      p.payload = enc_frames[k];
      p.pt = kOpusPt;
    }
    if (bad) { if (U(rng) < p_bg) bad = false; } else { if (U(rng) < p_gb) bad = true; }
    if (bad) { lost++; continue; }
    // Send time k*ptime, plus a base delay and jitter (exponential-ish spikes).
    double j = jitter_ms > 0 ? -std::log(1 - U(rng)) * jitter_ms : 0;
    p.arrival_ms = (int64_t)(k * ptime + 50 + j);
    pkts.push_back(std::move(p));
  }
  std::stable_sort(pkts.begin(), pkts.end(), [](const Pkt& a, const Pkt& b) { return a.arrival_ms < b.arrival_ms; });

  SimulatedClock clock(Timestamp::Millis(1000000));
  NetEq::Config cfg;
  cfg.sample_rate_hz = 48000;
  cfg.max_packets_in_buffer = 100;
  cfg.enable_fast_accelerate = true;
  cfg.enable_muted_state = true;
  auto factory = rtc::make_ref_counted<OpusOnlyFactory>();
  std::unique_ptr<NetEq> neteq = DefaultNetEqFactory().CreateNetEq(cfg, factory, &clock);
  neteq->RegisterPayloadType(kOpusPt, SdpAudioFormat("opus", 48000, 2, {{"useinbandfec", "1"}}));
  neteq->RegisterPayloadType(kRedPt, SdpAudioFormat("red", 48000, 2));

  FILE* out = fopen(argv[13], "wb");
  size_t next = 0;
  int64_t end_ms = (int64_t)frames * ptime + 500;
  AudioFrame frame;
  for (int64_t t = 0; t < end_ms; t += 10) {
    while (next < pkts.size() && pkts[next].arrival_ms <= t) {
      RTPHeader h;
      h.payloadType = pkts[next].pt;
      h.sequenceNumber = pkts[next].seq;
      h.timestamp = pkts[next].ts;
      h.ssrc = 1234;
      neteq->InsertPacket(h, pkts[next].payload);
      next++;
    }
    bool muted = false;
    neteq->GetAudio(&frame, &muted);
    fwrite(frame.data(), 2, frame.samples_per_channel_ * frame.num_channels_, out);
    clock.AdvanceTimeMilliseconds(10);
  }
  fclose(out);
  NetEqLifetimeStatistics st = neteq->GetLifetimeStatistics();
  printf("{\"lost\":%d,\"concealed\":%llu,\"silent_concealed\":%llu,\"concealment_events\":%llu,\"total\":%llu,"
         "\"inserted_accel\":%llu,\"removed_accel\":%llu,\"jb_delay_ms\":%.1f}\n",
         lost, (unsigned long long)st.concealed_samples, (unsigned long long)st.silent_concealed_samples,
         (unsigned long long)st.concealment_events, (unsigned long long)st.total_samples_received,
         (unsigned long long)st.inserted_samples_for_deceleration,
         (unsigned long long)st.removed_samples_for_acceleration,
         st.jitter_buffer_emitted_count ? (double)st.jitter_buffer_delay_ms / st.jitter_buffer_emitted_count : 0.0);
  return 0;
}
