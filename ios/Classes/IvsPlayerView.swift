import UIKit
import AVFoundation
import Flutter
import AmazonIVSPlayer

class IvsPlayerView: NSObject, FlutterPlatformView, FlutterStreamHandler, IVSPlayer.Delegate {
    
    private var playerView: UIView
    private var _methodChannel: FlutterMethodChannel?
    private var _eventChannel: FlutterEventChannel?
    private var _eventSink: FlutterEventSink?
    private var players: [String: IVSPlayer] = [:] // Dictionary to manage multiple players
    private var playerViews: [String: IVSPlayerView] = [:]
    private var playerId: String?
    
    // Constants for better code maintenance
    private enum PlayerConstants {
        static let syncThreshold: TimeInterval = 0.5 // Tolerance for sync in seconds
        static let defaultBufferSize: TimeInterval = 2.0 // Buffer size for livestreams in seconds
    }
    
    func view() -> UIView {
        return playerView
    }
    
    // MARK: - IVSPlayer Delegate Methods
    
    func player(_ player: IVSPlayer, didChangeState state: IVSPlayer.State) {
        guard let eventSink = _eventSink,
              let id = getPlayerIdFor(player: player) else { return }
        
        let dict: [String: Any] = [
            "playerId": id,
            "state": state.rawValue,
            "stateDescription": stateToString(state)
        ]
        eventSink(dict)
    }

    func player(_ player: IVSPlayer, didChangeDuration time: CMTime) {
        guard let eventSink = _eventSink,
              let id = getPlayerIdFor(player: player) else { return }
        
        let dict: [String: Any] = [
            "playerId": id,
            "duration": time.seconds
        ]
        eventSink(dict)
    }

    func player(_ player: IVSPlayer, didChangeSyncTime time: CMTime) {
        guard let eventSink = _eventSink,
              let id = getPlayerIdFor(player: player) else { return }
        
        let dict: [String: Any] = [
            "playerId": id,
            "syncTime": time.seconds
        ]
        eventSink(dict)
        
    }

    func player(_ player: IVSPlayer, didChangeQuality quality: IVSQuality?) {
        guard let eventSink = _eventSink,
              let id = getPlayerIdFor(player: player) else { return }
        
        let qualityInfo: [String: Any] = [
            "name": quality?.name ?? "",
            "bitrate": quality?.bitrate ?? 0,
            "codecs": quality?.codecs ?? ""
        ]
        
        let dict: [String: Any] = [
            "playerId": id,
            "quality": quality?.name ?? "",
            "qualityInfo": qualityInfo
        ]
        eventSink(dict)
    }
    
    func player(_ player: IVSPlayer, didOutputCue cue: IVSCue) {
        guard let eventSink = _eventSink,
              let id = getPlayerIdFor(player: player) else { return }
        
        if let textMetadataCue = cue as? IVSTextMetadataCue {
            let dict: [String: Any] = [
                "playerId": id,
                "type": "metadata",
                "metadata": textMetadataCue.text,
                "startTime": textMetadataCue.startTime.epoch,
                "endTime": textMetadataCue.endTime.epoch,
            ]
            eventSink(dict)
        }
    }

    func player(_ player: IVSPlayer, didFailWithError error: any Error) {
        guard let eventSink = _eventSink,
              let id = getPlayerIdFor(player: player) else { return }
        
        let dict: [String: Any] = [
            "playerId": id,
            "type": "error",
            "error": error.localizedDescription,
            "code": (error as NSError).code
        ]
        eventSink(dict)
        
        // Attempt to recover from error
        handlePlayerError(player: player, error: error)
    }

    func player(_ player: IVSPlayer, didSeekTo time: CMTime) {
        guard let eventSink = _eventSink,
              let id = getPlayerIdFor(player: player) else { return }
        
        let dict: [String: Any] = [
            "playerId": id,
            "seekedToTime": time.seconds
        ]
        eventSink(dict)
    }
    
    // MARK: - Flutter Stream Handler
    
    func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        self._eventSink = events
        return nil
    }
    
    func onCancel(withArguments arguments: Any?) -> FlutterError? {
        self._eventSink = nil
        return nil
    }
    
    // MARK: - Initialization
    
    init(_ frame: CGRect,
         viewId: Int64,
         args: Any?,
         messenger: FlutterBinaryMessenger
    ) {
        _methodChannel = FlutterMethodChannel(
            name: "ivs_player", binaryMessenger: messenger
        )
        _eventChannel = FlutterEventChannel(name: "ivs_player_event", binaryMessenger: messenger)
        playerView = UIView(frame: frame)
        super.init()
        _methodChannel?.setMethodCallHandler(onMethodCall)
        _eventChannel?.setStreamHandler(self)
         
    }
    
    // MARK: - Method Handler
    
    func onMethodCall(call: FlutterMethodCall, result: FlutterResult) {
        print("MethodCall: \(call.method)")
        switch(call.method) {
        case "createPlayer":
            guard let args = call.arguments as? [String: Any],
                  let playerId = args["playerId"] as? String else {
                result(FlutterError(code: "INVALID_ARGS", message: "Invalid arguments for createPlayer", details: nil))
                return
            }
            createPlayer(playerId: playerId)
            result(true)
            
        case "multiPlayer":
            guard let args = call.arguments as? [String: Any],
                  let urls = args["urls"] as? [String] else {
                result(FlutterError(code: "INVALID_ARGS", message: "Invalid arguments for multiPlayer", details: nil))
                return
            }
            multiPlayer(urls)
            result("Players created successfully")
            
        case "selectPlayer":
            guard let args = call.arguments as? [String: Any],
                  let playerId = args["playerId"] as? String else {
                result(FlutterError(code: "INVALID_ARGS", message: "Invalid arguments for selectPlayer", details: nil))
                return
            }
            selectPlayer(playerId: playerId)
            result(true)
            
        case "startPlayer":
            guard let args = call.arguments as? [String: Any],
                  let url = args["url"] as? String,
                  let autoPlay = args["autoPlay"] as? Bool else {
                result(FlutterError(code: "INVALID_ARGS", message: "Invalid arguments for startPlayer", details: nil))
                return
            }
            startPlayer(url: url, autoPlay: autoPlay)
            result(true)
            
        case "stopPlayer":
            if let playerId = self.playerId {
                stopPlayer(playerId: playerId)
            }
            result(true)
            
        case "dispose":
            disposeAllPlayer()
            result(true)
            
        case "mute":
            guard let playerId = self.playerId else {
                result(FlutterError(code: "NO_ACTIVE_PLAYER", message: "No active player", details: nil))
                return
            }
            mutePlayer(playerId: playerId)
            result(true)
            
        case "pause":
            guard let playerId = self.playerId else {
                result(FlutterError(code: "NO_ACTIVE_PLAYER", message: "No active player", details: nil))
                return
            }
            pausePlayer(playerId: playerId)
            result(true)
            
        case "resume":
            guard let playerId = self.playerId else {
                result(FlutterError(code: "NO_ACTIVE_PLAYER", message: "No active player", details: nil))
                return
            }
            resumePlayer(playerId: playerId)
            result(true)
            
        case "seek":
            guard let args = call.arguments as? [String: Any],
                  let playerId = self.playerId,
                  let time = args["time"] as? String else {
                result(FlutterError(code: "INVALID_ARGS", message: "Invalid arguments for seek", details: nil))
                return
            }
            seekPlayer(playerId: playerId, time)
            result(true)
            
        case "position":
            if let playerId = self.playerId {
                result(getPosition(playerId: playerId))
            } else {
                result("0")
            }
            
        case "qualities":
            if let playerId = self.playerId {
                let qualities = getQualities(playerId: playerId)
                result(qualities)
            } else {
                result([])
            }
            
        case "setQuality":
            guard let args = call.arguments as? [String: Any],
                  let playerId = self.playerId,
                  let quality = args["quality"] as? String else {
                result(FlutterError(code: "INVALID_ARGS", message: "Invalid arguments for setQuality", details: nil))
                return
            }
            setQuality(playerId: playerId, quality)
            result(true)
            
        case "autoQuality":
            guard let playerId = self.playerId else {
                result(FlutterError(code: "NO_ACTIVE_PLAYER", message: "No active player", details: nil))
                return
            }
            toggleAutoQuality(playerId: playerId)
            result(true)
            
        case "isAuto":
            guard let playerId = self.playerId else {
                result(false)
                return
            }
            result(isAuto(playerId: playerId))
            
        case "getScreenshot":
            guard let args = call.arguments as? [String: Any],
                  let url = args["url"] as? String else {
                result(FlutterError(code: "INVALID_ARGS", message: "Invalid arguments for getScreenshot", details: nil))
                return
            }
            
            if let screenshot = getScreenShot(url: url) {
                result(screenshot)
            } else {
                result(nil)
            }
            
        default:
            result(FlutterMethodNotImplemented)
        }
    }
    
    // MARK: - Player Management Methods
    
    func createPlayer(playerId: String) {
        if players[playerId] != nil {
            return
        }
        
        let player = IVSPlayer()
        player.delegate = self
        
        // Configure player for optimal livestream playback
//        player.setRebufferToLive(true)
        player.setLiveLowLatencyEnabled(true)
        
        self.playerId = playerId
        players[playerId] = player
        playerViews[playerId] = IVSPlayerView()
        playerViews[playerId]?.player = player
        
        if let url = URL(string: playerId) {
            player.load(url)
        }
    }
    
    func multiPlayer(_ urls: [String]) {
        // Use the first URL as the initial active player
        self.playerId = urls.first
        
        for url in urls {
            let player = IVSPlayer()
            player.delegate = self
            
            // Configure for livestream
//            player.setRebufferToLive(true)
            player.setLiveLowLatencyEnabled(true)
             
            let playerId = url
            players[playerId] = player
            playerViews[playerId] = IVSPlayerView()
            playerViews[playerId]?.player = player
            
            if let streamUrl = URL(string: url) {
                player.load(streamUrl)
            }
            
            // Start with muted except for the first one
            player.volume = 0
        }
        
        // Play all players and attach the first one for preview
        if let firstPlayer = urls.first, let player = players[firstPlayer] {
            player.volume = 1
            player.play()
            
            if let playerView = playerViews[firstPlayer] {
                attachPreview(container: self.playerView, preview: playerView)
            }
            
            // Play other players with a small delay to help with initial synchronization
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                for (id, player) in self.players {
                    if id != firstPlayer {
                        player.play()
                    }
                }
            }
        }
    }
    
    func selectPlayer(playerId: String) {
        guard let player = players[playerId],
              let playerView = playerViews[playerId] else { return }
        
        let previousPlayer = self.playerId
        self.playerId = playerId
        
        // Update delegates if needed
        if let previousId = previousPlayer, previousId != playerId {
            players[previousId]?.delegate = nil
            player.delegate = self
        }
        
        // Mute all players except the selected one
        for (id, p) in players {
            p.volume = (id == playerId) ? 1.0 : 0.0
        }
         
        
        // Smooth transition for UI
        UIView.animate(withDuration: 0.2, animations: {
            self.playerView.alpha = 0
        }) { _ in
            // Update the preview
            self.attachPreview(container: self.playerView, preview: playerView)
            
            // Fade in the new preview
            UIView.animate(withDuration: 0.3) {
                self.playerView.alpha = 1
            }
        }
        
        updateEventsOfCurrentPlayer()
    }
    
    func updateEventsOfCurrentPlayer() {
        guard let playerId = self.playerId, let player = players[playerId] else { return }
        
        player.delegate = self
        
        // Send comprehensive state update
        let dict: [String: Any] = [
            "playerId": playerId,
            "state": player.state.rawValue,
            "syncTime": player.syncTime.seconds,
            "position": player.position.seconds,
            "quality": player.quality?.name ?? "",
            "autoQualityMode": player.autoQualityMode
        ]
        _eventSink?(dict)
    }
    
    func startPlayer(url: String, autoPlay: Bool) {
        guard let player = players[url] else {
            // Create player if it doesn't exist
            createPlayer(playerId: url)
            guard let newPlayer = players[url], let playerView = playerViews[url] else { return }
            
            self.playerId = url
            if autoPlay {
                newPlayer.play()
            }
            
            attachPreview(container: self.playerView, preview: playerView)
            return
        }
        
        self.playerId = url
        if autoPlay {
            player.play()
        }
        selectPlayer(playerId: url)
    }
    
    func stopPlayer(playerId: String) {
        guard let player = players[playerId] else { return }
        
        // Properly cleanup resources
        player.pause()
        player.delegate = nil
        
        // Remove the player and view
        players.removeValue(forKey: playerId)
        playerViews.removeValue(forKey: playerId)
        
        // Update active player if needed
        if playerId == self.playerId {
            self.playerId = players.keys.first
            if let newId = self.playerId, let playerView = playerViews[newId] {
                players[newId]?.volume = 1
                players[newId]?.delegate = self
                attachPreview(container: self.playerView, preview: playerView)
            } else {
                // Clear the view if no players left
                self.playerView.subviews.forEach { $0.removeFromSuperview() }
            }
        }
    }
    
    func disposeAllPlayer() {
        let keys = Array(players.keys)
        for key in keys {
            if let player = players[key] {
                player.pause()
                player.delegate = nil
                players.removeValue(forKey: key)
                playerViews.removeValue(forKey: key)
            }
        }
        
        playerId = nil
        self.playerView.subviews.forEach { $0.removeFromSuperview() }
    }
    
    // MARK: - Player Control Methods
    
    func mutePlayer(playerId: String) {
        guard let player = players[playerId] else { return }
        player.volume = player.volume == 0 ? 1 : 0
        
        // Report volume change
        if player.volume == 0 {
            _eventSink?(["playerId": playerId, "type": "volume", "isMuted": true])
        } else {
            _eventSink?(["playerId": playerId, "type": "volume", "isMuted": false])
        }
    }
    
    func pausePlayer(playerId: String) {
        guard let player = players[playerId] else { return }
        player.pause()
    }
    
    func resumePlayer(playerId: String) {
        guard let player = players[playerId] else { return }
        player.play()
    }
    
    func seekPlayer(playerId: String, _ timeString: String) {
        guard let player = players[playerId],
              let timeValue = Double(timeString) else { return }
        
        player.seek(to: CMTimeMakeWithSeconds(timeValue, preferredTimescale: 1000))
    }
    
    func getPosition(playerId: String) -> String {
        guard let player = players[playerId] else { return "0" }
        return String(format: "%.3f", player.position.seconds)
    }
    
    func getQualities(playerId: String) -> [[String: Any]] {
        guard let player = players[playerId] else { return [] }
        
        // Return detailed quality information
        return player.qualities.map { quality in
            return [
                "name": quality.name,
                "bitrate": quality.bitrate,
                "codecs": quality.codecs
            ]
        }
    }
    
    func setQuality(playerId: String, _ quality: String) {
        guard let player = players[playerId] else { return }
        let qualities = player.qualities
        let qualityToChange = qualities.first { $0.name == quality }
        
        if let qualityToSet = qualityToChange {
            player.setQuality(qualityToSet, adaptive: false)
            _eventSink?(["playerId": playerId, "type": "qualityChanged", "quality": quality])
        }
    }
    
    func toggleAutoQuality(playerId: String) {
        guard let player = players[playerId] else { return }
        player.autoQualityMode.toggle()
        _eventSink?(["playerId": playerId, "type": "autoQuality", "enabled": player.autoQualityMode])
    }
    
    func isAuto(playerId: String) -> Bool {
        guard let player = players[playerId] else { return false }
        return player.autoQualityMode
    }
    
    // MARK: - Screenshot Method
    
    func getScreenShot(url: String) -> [UInt8]? {
        guard let videoURL = URL(string: url) else {
            print("Invalid URL for screenshot")
            return nil
        }
        
        // Create an AVAsset and AVAssetImageGenerator
        let asset = AVAsset(url: videoURL) // Fixed: Use the provided URL instead of hardcoded one
        let imageGenerator = AVAssetImageGenerator(asset: asset)
        imageGenerator.appliesPreferredTrackTransform = true
        
        // Define the time for the screenshot (1 second mark)
        let time = CMTime(seconds: 1.0, preferredTimescale: 600)
        
        do {
            // Generate the CGImage
            let cgImage = try imageGenerator.copyCGImage(at: time, actualTime: nil)
            // Convert to UIImage
            let image = UIImage(cgImage: cgImage)
            guard let imageData = image.pngData() else { return nil }
            return [UInt8](imageData)
        } catch {
            print("Failed to generate screenshot: \(error.localizedDescription)")
            return nil
        }
    }
    
    // MARK: - Helper Methods
    
    private func attachPreview(container: UIView, preview: UIView) {
        // Clear current view, and then attach the new view
        container.subviews.forEach { $0.removeFromSuperview() }
        preview.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(preview)
        
        NSLayoutConstraint.activate([
            preview.topAnchor.constraint(equalTo: container.topAnchor),
            preview.bottomAnchor.constraint(equalTo: container.bottomAnchor),
            preview.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            preview.trailingAnchor.constraint(equalTo: container.trailingAnchor)
        ])
    }
    
    private func getPlayerIdFor(player: IVSPlayer) -> String? {
        return players.first(where: { $0.value === player })?.key
    }
    
    private func handlePlayerError(player: IVSPlayer, error: Error) {
        // Attempt to recover based on error type
        let nsError = error as NSError
        
        // If network-related error, attempt to reconnect
        if nsError.domain == NSURLErrorDomain {
            // Wait briefly and try to reload
            DispatchQueue.main.asyncAfter(deadline: .now() + 5.0) {
                if let url = player.path {
                    player.load(url)
                    player.play()
                }
            }
        }
    }
    
    private func stateToString(_ state: IVSPlayer.State) -> String {
        switch state {
        case .idle: return "idle"
        case .ready: return "ready"
        case .buffering: return "buffering"
        case .playing: return "playing"
        case .ended: return "ended"
        @unknown default: return "unknown"
        }
    }
}
