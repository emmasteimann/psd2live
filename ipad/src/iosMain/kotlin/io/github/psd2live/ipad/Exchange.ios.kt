package io.github.psd2live.ipad

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject
import platform.CoreGraphics.CGRectMake
import kotlinx.cinterop.useContents

/** The view controller on top, to present the picker and the share sheet from. */
private fun topController(): UIViewController? {
	var controller = UIApplication.sharedApplication.keyWindow?.rootViewController
	while (controller?.presentedViewController != null) controller = controller.presentedViewController
	return controller
}

/** Kept alive while the picker is up: UIKit holds its delegate weakly. */
private var pickerDelegate: NSObject? = null

@OptIn(ExperimentalForeignApi::class)
actual object Exchange {
	actual fun pick(onPicked: (List<String>) -> Unit) {
		val controller = topController() ?: return
		val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem), asCopy = true)
		picker.allowsMultipleSelection = true
		val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
			override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
				// The picker hands copies in a temporary inbox: each moves into Documents (an older file of the name is replaced).
				val documents = RigFiles.psdPath("").trimEnd('/')
				val names = didPickDocumentsAtURLs.mapNotNull { it as? NSURL }.mapNotNull { url ->
					val name = url.lastPathComponent ?: return@mapNotNull null
					val ext = name.substringAfterLast('.', "").lowercase()
					if (ext != "psd" && ext != "psd2live") return@mapNotNull null
					val target = "$documents/$name"
					val files = NSFileManager.defaultManager
					files.removeItemAtPath(target, null)
					if (files.moveItemAtPath(url.path ?: return@mapNotNull null, target, null)) name else null
				}
				pickerDelegate = null
				onPicked(names)
			}

			override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) { pickerDelegate = null; onPicked(emptyList()) }
		}
		pickerDelegate = delegate
		picker.delegate = delegate
		controller.presentViewController(picker, animated = true, completion = null)
	}

	actual fun share(paths: List<String>) {
		val controller = topController() ?: return
		val items = paths.map { NSURL.fileURLWithPath(it) }
		val sheet = UIActivityViewController(activityItems = items, applicationActivities = null)
		// On the iPad the sheet is a popover and needs an anchor: the middle of the top of the screen.
		sheet.popoverPresentationController?.let { popover ->
			popover.sourceView = controller.view
			val width = controller.view.bounds.useContents { size.width }
			popover.sourceRect = CGRectMake(width / 2.0, 80.0, 1.0, 1.0)
		}
		controller.presentViewController(sheet, animated = true, completion = null)
	}
}
