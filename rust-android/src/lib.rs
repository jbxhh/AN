slint::slint! {
    export component MainWindow inherits Window {
        title: "ANONERO Rust Test";
        preferred-width: 360px;
        preferred-height: 720px;
        background: #101114;

        VerticalLayout {
            alignment: center;
            spacing: 18px;
            padding: 24px;

            Text {
                text: "ANONERO";
                color: #f2f2f2;
                font-size: 28px;
                font-weight: 700;
                horizontal-alignment: center;
            }

            Text {
                text: "PURE RUST APK TEST";
                color: #d6a85f;
                font-size: 14px;
                horizontal-alignment: center;
            }

            Rectangle {
                background: #1b1d22;
                border-radius: 14px;
                padding: 18px;
                VerticalLayout {
                    spacing: 10px;
                    Text { text: "Rust Android shell"; color: #ffffff; font-size: 18px; }
                    Text {
                        text: "This isolated build tests Rust UI and APK packaging only. Wallet recovery, blockchain sync, balances, and transactions are not migrated yet.";
                        color: #b8bac2;
                        font-size: 14px;
                        wrap: word-wrap;
                    }
                }
            }

            Text {
                text: "ARM64 • Rust • No C++ wallet library";
                color: #8e929d;
                font-size: 12px;
                horizontal-alignment: center;
            }
        }
    }
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
fn android_main(app: slint::android::AndroidApp) {
    slint::android::init(app).expect("initialize Slint Android backend");
    MainWindow::new().expect("create main window").run().expect("run UI");
}
