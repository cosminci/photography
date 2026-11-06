package photography.adapters.sqlite

import org.typelevel.doobie.Fragment
import org.typelevel.doobie.implicits.*
import photography.adapters.sqlite.SqliteCodecs.given
import photography.domain.album.AlbumId

object SqliteAlbumQueries:
  def interiorSpreadCount(album: AlbumId): Fragment =
    sql"""SELECT
      (SELECT count(*) FROM spread WHERE album_id = ${album.value}) +
      (SELECT count(*) FROM chapter c JOIN spread s ON s.chapter_id = c.id WHERE c.album_id = ${album.value})"""
