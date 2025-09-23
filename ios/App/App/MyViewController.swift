//
//  MyViewController.swift
//  Audiobookshelf
//
//  Created by advplyr on 1/12/25.
//

import UIKit
import Capacitor

class MyViewController: CAPBridgeViewController {

    override func viewDidLoad() {
        super.viewDidLoad()

        // Do any additional setup after loading the view.
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        updateSafeAreaInsets()
    }

    private func updateSafeAreaInsets() {
        if let webView = self.webView {
            let insets = view.safeAreaInsets
            let js = """
            document.documentElement.style.setProperty('--safe-area-inset-top', '\(insets.top)px');
            document.documentElement.style.setProperty('--safe-area-inset-bottom', '\(insets.bottom)px');
            document.documentElement.style.setProperty('--safe-area-inset-left', '\(insets.left)px');
            document.documentElement.style.setProperty('--safe-area-inset-right', '\(insets.right)px');
            """
            webView.evaluateJavaScript(js, completionHandler: nil)
        }
    }

    override open func capacitorDidLoad() {
        bridge?.registerPluginInstance(AbsDatabase())
        bridge?.registerPluginInstance(AbsAudioPlayer())
        bridge?.registerPluginInstance(AbsDownloader())
        bridge?.registerPluginInstance(AbsFileSystem())
        bridge?.registerPluginInstance(AbsLogger())
    }


    /*
    // MARK: - Navigation

    // In a storyboard-based application, you will often want to do a little preparation before navigation
    override func prepare(for segue: UIStoryboardSegue, sender: Any?) {
        // Get the new view controller using segue.destination.
        // Pass the selected object to the new view controller.
    }
    */

}
