// Loaded in <head>, before the page paints, so hidden grades never flash up on a projector.
try {
    if (localStorage.getItem("notenfuchs.privacy") === "on") {
        document.documentElement.classList.add("privacy-on");
    }
} catch (e) {
    // No storage (private window, blocked site data): the switch still works until the next page.
}
