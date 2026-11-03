// Web admin enhancements (no inline script: the CSP only allows 'self').
// Copy buttons: <button type="button" data-copy="#input-id" hidden>, revealed here.
(function () {
  document.querySelectorAll("[data-copy]").forEach(function (button) {
    var input = document.querySelector(button.getAttribute("data-copy"));
    if (!input) return;
    var label = button.textContent;
    var timer;
    function feedback(text) {
      button.textContent = text;
      clearTimeout(timer);
      timer = setTimeout(function () { button.textContent = label; }, 2000);
    }
    function fallback() {
      input.focus();
      input.select();
      try {
        feedback(document.execCommand("copy") ? "Lien copié ✓" : "Sélectionné : Ctrl+C");
      } catch (e) {
        feedback("Sélectionné : Ctrl+C");
      }
    }
    input.addEventListener("focus", function () { input.select(); });
    button.hidden = false;
    button.addEventListener("click", function () {
      if (navigator.clipboard && window.isSecureContext) {
        navigator.clipboard.writeText(input.value).then(function () { feedback("Lien copié ✓"); }, fallback);
      } else {
        fallback();
      }
    });
  });
})();

// Confirmation before destructive actions: <form data-confirm="Question ?">.
document.addEventListener("submit", function (event) {
  var question = event.target.getAttribute && event.target.getAttribute("data-confirm");
  if (question && !window.confirm(question)) event.preventDefault();
});
