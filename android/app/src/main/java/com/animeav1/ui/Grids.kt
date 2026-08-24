package com.animeav1.ui

/**
 * Columnas de las rejillas de póster: Catálogo, Horario, Mi Lista y Búsqueda usan LA MISMA, y sus
 * skeletons de carga también (si no, se ve un salto al llegar los datos).
 *
 * ⚠️ Va de la mano de `@dimen/poster_card_height`: la altura del póster está fijada en dp, así que
 * cambiar el número de columnas cambia el ancho de celda y **rompe la proporción del arte** si no se
 * ajusta la altura. Las portadas de animeav1 son **2:3** (0,708), y con 5 columnas la celda salía casi
 * cuadrada: se descartaba ~21 % del alto con `centerCrop`, justo la franja donde suele ir el logotipo
 * de la serie. Si tocas una, mide la otra.
 */
const val GRID_COLUMNS = 6
