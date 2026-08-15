// Petit mini-site statique HTML + JS, aucune dépendance côté build.
// Deux comportements : surligner le lien de navigation actif, et un bouton "copier" sur chaque
// bloc de code (aide à la copie des extraits YAML/commandes montrés sur ce site).

document.addEventListener("DOMContentLoaded", () => {
  highlightActiveNavLink();
  addCopyButtons();
});

function highlightActiveNavLink() {
  const currentPage = window.location.pathname.split("/").pop() || "index.html";
  document.querySelectorAll(".navbar-nav .nav-link").forEach((link) => {
    const linkPage = link.getAttribute("href");
    if (linkPage === currentPage) {
      link.classList.add("active");
      link.setAttribute("aria-current", "page");
    }
  });
}

function addCopyButtons() {
  document.querySelectorAll("pre.rpgq-code").forEach((block) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "btn btn-sm btn-outline-secondary rpgq-copy-btn";
    button.textContent = "Copier";
    button.addEventListener("click", () => copyBlock(block, button));
    block.appendChild(button);
  });
}

function copyBlock(block, button) {
  const code = block.querySelector("code");
  const text = code ? code.textContent : block.textContent;
  navigator.clipboard.writeText(text.trim()).then(() => {
    const original = button.textContent;
    button.textContent = "Copié !";
    setTimeout(() => {
      button.textContent = original;
    }, 1500);
  });
}
