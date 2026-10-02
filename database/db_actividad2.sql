CREATE DATABASE IF NOT EXISTS db_actividad2
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE db_actividad2;

CREATE TABLE IF NOT EXISTS usuarios (
    id INT NOT NULL AUTO_INCREMENT,
    nombre VARCHAR(100) NOT NULL,
    usuario VARCHAR(50) NOT NULL,
    password VARCHAR(128) NULL,
    fecha_registro TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_usuario (usuario)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS foros (
    id INT NOT NULL AUTO_INCREMENT,
    creador_id INT NOT NULL,
    titulo VARCHAR(150) NOT NULL,
    descripcion TEXT NOT NULL,
    fecha_creacion TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_foro_creador FOREIGN KEY (creador_id) REFERENCES usuarios(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS mensajes (
    id INT NOT NULL AUTO_INCREMENT,
    foro_id INT NOT NULL,
    autor_id INT NOT NULL,
    titulo VARCHAR(150) NOT NULL,
    contenido TEXT NOT NULL,
    fecha_creacion TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    inapropiado BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    CONSTRAINT fk_mensaje_foro FOREIGN KEY (foro_id) REFERENCES foros(id),
    CONSTRAINT fk_mensaje_autor FOREIGN KEY (autor_id) REFERENCES usuarios(id)
) ENGINE=InnoDB;

SET @sql_moderacion = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mensajes'
       AND COLUMN_NAME = 'inapropiado') = 0,
    'ALTER TABLE mensajes ADD COLUMN inapropiado BOOLEAN NOT NULL DEFAULT FALSE',
    'SELECT 1'
);
PREPARE ajuste_moderacion FROM @sql_moderacion;
EXECUTE ajuste_moderacion;
DEALLOCATE PREPARE ajuste_moderacion;
