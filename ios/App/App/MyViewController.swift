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
    
    override open func capacitorDidLoad() {
        bridge?.registerPluginInstance(AbsDatabase())
        bridge?.registerPluginInstance(AbsAudioPlayer())
        bridge?.registerPluginInstance(AbsDownloader())
        bridge?.registerPluginInstance(AbsFileSystem())
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
