using System.ComponentModel.DataAnnotations.Schema;

namespace webapi.Models
{
    [Table("inspection_position_photos")]
    public class InspectionPositionPhoto
    {
        [Column("id")]
        public int Id { get; set; }

        [Column("template_id")]
        public int TemplateId { get; set; }

        [Column("photo_path")]
        public string PhotoPath { get; set; } = string.Empty;

        [Column("thumbnail_path")]
        public string? ThumbnailPath { get; set; }

        [Column("photo_order")]
        public int PhotoOrder { get; set; } = 0;

        [Column("created_at")]
        public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    }
}
