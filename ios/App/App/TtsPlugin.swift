import Foundation
import AVFoundation
import Capacitor
import MediaPlayer

@objc(TtsPlugin)
public class TtsPlugin: CAPPlugin, AVSpeechSynthesizerDelegate {

    private let synthesizer = AVSpeechSynthesizer()
    private var rate: Float = AVSpeechUtteranceDefaultSpeechRate
    private var pitch: Float = 1.0
    private var selectedVoiceIdentifier: String? = nil
    private var currentUtteranceId: String? = nil

    public override func load() {
        synthesizer.delegate = self
        setupAudioSession()
        setupRemoteCommandCenter()
    }

    private func setupAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio, options: [.mixWithOthers])
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            print("[TtsPlugin] AVAudioSession setup failed: \(error)")
        }
    }

    private func setupRemoteCommandCenter() {
        let commandCenter = MPRemoteCommandCenter.shared()
        commandCenter.playCommand.addTarget { [weak self] _ in
            self?.notifyListeners("ttsResume", data: [:])
            return .success
        }
        commandCenter.pauseCommand.addTarget { [weak self] _ in
            self?.synthesizer.pauseSpeaking(at: .word)
            self?.notifyListeners("ttsPause", data: [:])
            return .success
        }
        commandCenter.nextTrackCommand.addTarget { [weak self] _ in
            self?.synthesizer.stopSpeaking(at: .word)
            self?.notifyListeners("ttsNext", data: [:])
            return .success
        }
        commandCenter.previousTrackCommand.addTarget { [weak self] _ in
            self?.synthesizer.stopSpeaking(at: .word)
            self?.notifyListeners("ttsPrev", data: [:])
            return .success
        }
    }

    private func updateNowPlaying(title: String = "Read Aloud") {
        var info = [String: Any]()
        info[MPMediaItemPropertyTitle] = title
        info[MPNowPlayingInfoPropertyPlaybackRate] = Double(rate)
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    @objc func initialize(_ call: CAPPluginCall) {
        call.resolve(["ready": true])
    }

    @objc func getVoices(_ call: CAPPluginCall) {
        let voices = AVSpeechSynthesisVoice.speechVoices().map { v -> [String: Any] in
            return ["name": v.name, "lang": v.language, "localService": true]
        }
        call.resolve(["voices": voices])
    }

    @objc func speak(_ call: CAPPluginCall) {
        guard let text = call.getString("text") else {
            call.reject("text required")
            return
        }
        let utteranceId = call.getString("utteranceId") ?? "tts_utt"
        let callRate = call.getFloat("rate") ?? rate
        let callPitch = call.getFloat("pitch") ?? pitch
        let voiceName = call.getString("voiceName")

        synthesizer.stopSpeaking(at: .immediate)

        let utterance = AVSpeechUtterance(string: text)
        utterance.rate = callRate
        utterance.pitchMultiplier = callPitch

        if let voiceName = voiceName {
            utterance.voice = AVSpeechSynthesisVoice.speechVoices().first { $0.name == voiceName }
        } else if let identifier = selectedVoiceIdentifier {
            utterance.voice = AVSpeechSynthesisVoice(identifier: identifier)
        }

        currentUtteranceId = utteranceId
        updateNowPlaying()
        synthesizer.speak(utterance)
        call.resolve(["started": true])
    }

    @objc func stop(_ call: CAPPluginCall) {
        synthesizer.stopSpeaking(at: .immediate)
        call.resolve(["stopped": true])
    }

    @objc func pause(_ call: CAPPluginCall) {
        synthesizer.pauseSpeaking(at: .word)
        call.resolve(["paused": true])
    }

    @objc func resume(_ call: CAPPluginCall) {
        if synthesizer.isPaused {
            synthesizer.continueSpeaking()
        }
        call.resolve(["resumed": true])
    }

    @objc func setRate(_ call: CAPPluginCall) {
        rate = call.getFloat("rate") ?? AVSpeechUtteranceDefaultSpeechRate
        call.resolve(["ok": true])
    }

    @objc func setPitch(_ call: CAPPluginCall) {
        pitch = call.getFloat("pitch") ?? 1.0
        call.resolve(["ok": true])
    }

    @objc func setVoice(_ call: CAPPluginCall) {
        if let voiceName = call.getString("voiceName") {
            selectedVoiceIdentifier = AVSpeechSynthesisVoice.speechVoices().first { $0.name == voiceName }?.identifier
        }
        call.resolve(["ok": true])
    }

    // MARK: - AVSpeechSynthesizerDelegate

    public func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didStart utterance: AVSpeechUtterance) {
        notifyListeners("ttsStart", data: ["utteranceId": currentUtteranceId ?? ""])
    }

    public func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        notifyListeners("ttsDone", data: ["utteranceId": currentUtteranceId ?? ""])
    }

    public func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        notifyListeners("ttsError", data: ["utteranceId": currentUtteranceId ?? "", "error": "cancelled"])
    }

    public func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer,
                                   willSpeakRangeOfSpeechString characterRange: NSRange,
                                   utterance: AVSpeechUtterance) {
        notifyListeners("ttsRangeStart", data: [
            "utteranceId": currentUtteranceId ?? "",
            "start": characterRange.location,
            "end": characterRange.location + characterRange.length
        ])
    }
}
