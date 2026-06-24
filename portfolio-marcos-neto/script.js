const links = [...document.querySelectorAll("[data-section], .mobile-dock a")];
const sections = [...document.querySelectorAll("main section[id]")];

const setActive = (id) => {
  links.forEach((link) => {
    const href = link.getAttribute("href") || "";
    const section = link.dataset.section || href.replace("#", "");
    link.classList.toggle("active", section === id);
  });
};

const observer = new IntersectionObserver(
  (entries) => {
    const visible = entries
      .filter((entry) => entry.isIntersecting)
      .sort((a, b) => b.intersectionRatio - a.intersectionRatio)[0];
    if (visible) setActive(visible.target.id);
  },
  { threshold: [0.3, 0.55, 0.75] }
);

sections.forEach((section) => observer.observe(section));
