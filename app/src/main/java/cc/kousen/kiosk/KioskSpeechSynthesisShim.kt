package cc.kousen.kiosk

object KioskSpeechSynthesisShim {
    val script: String = """
        (function () {
          if (window.__kousenSpeechSynthesisShimInstalled) return;
          if (!window.KousenNativeTts) return;

          window.__kousenSpeechSynthesisShimInstalled = true;

          function KousenSpeechSynthesisUtterance(text) {
            this.text = text || "";
            this.lang = "";
            this.voice = null;
            this.volume = 1;
            this.rate = 1;
            this.pitch = 1;
            this.onstart = null;
            this.onend = null;
            this.onerror = null;
          }

          function toPayload(utterance) {
            if (typeof utterance === "string") {
              return { text: utterance };
            }
            utterance = utterance || {};
            return {
              text: String(utterance.text || ""),
              lang: String(utterance.lang || document.documentElement.lang || navigator.language || ""),
              rate: Number(utterance.rate || 1),
              pitch: Number(utterance.pitch || 1),
              utteranceId: "kousen-" + Date.now() + "-" + Math.random().toString(36).slice(2)
            };
          }

          var bridge = {
            pending: false,
            paused: false,
            speaking: false,
            onvoiceschanged: null,
            getVoices: function () {
              return [];
            },
            speak: function (utterance) {
              var payload = toPayload(utterance);
              if (!payload.text) return;

              this.pending = false;
              this.paused = false;
              this.speaking = true;

              try {
                if (utterance && typeof utterance.onstart === "function") {
                  utterance.onstart({ type: "start", target: utterance });
                }
                window.KousenNativeTts.speak(JSON.stringify(payload));
                if (utterance && typeof utterance.onend === "function") {
                  window.setTimeout(function () {
                    utterance.onend({ type: "end", target: utterance });
                  }, 0);
                }
              } catch (error) {
                this.speaking = false;
                if (utterance && typeof utterance.onerror === "function") {
                  utterance.onerror({ type: "error", error: error, target: utterance });
                }
              }
            },
            cancel: function () {
              this.pending = false;
              this.paused = false;
              this.speaking = false;
              window.KousenNativeTts.cancel();
            },
            pause: function () {
              this.paused = true;
              this.cancel();
            },
            resume: function () {
              this.paused = false;
            }
          };

          try {
            Object.defineProperty(window, "SpeechSynthesisUtterance", {
              configurable: true,
              writable: true,
              value: KousenSpeechSynthesisUtterance
            });
          } catch (error) {
            window.SpeechSynthesisUtterance = KousenSpeechSynthesisUtterance;
          }

          try {
            Object.defineProperty(window, "speechSynthesis", {
              configurable: true,
              writable: true,
              value: bridge
            });
          } catch (error) {
            window.speechSynthesis = bridge;
          }

          console.info("Kousen native speech synthesis bridge installed");
        })();
    """.trimIndent()
}
