ivec2 wrapCell(ivec2 cell) {
    if (cell.x < 0) cell.x += uGridWidth;
    else if (cell.x >= uGridWidth) cell.x -= uGridWidth;
    if (cell.y < 0) cell.y += uGridHeight;
    else if (cell.y >= uGridHeight) cell.y -= uGridHeight;
    return cell;
}

ivec2 stencilOrigin(ivec2 home, int radius) {
    // Bound offsets to a single wrap even when the stencil covers the complete
    // tiny periodic grid. GLSL signed remainder is undefined for negatives.
    return home - min(ivec2(radius), ivec2(uGridWidth - 1, uGridHeight - 1));
}

// Enumerate each periodic cell once even for a viewport smaller than the stencil.
ivec2 stencilSize(int radius) {
    return min(ivec2(2 * radius + 1), ivec2(uGridWidth, uGridHeight));
}
